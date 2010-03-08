/*
 * HA-JDBC: High-Availability JDBC
 * Copyright 2004-2009 Paul Ferraro
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.sync;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.Dialect;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.SynchronizationContext;
import net.sf.hajdbc.SynchronizationStrategy;
import net.sf.hajdbc.cache.TableProperties;
import net.sf.hajdbc.cache.UniqueConstraint;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.SQLExceptionFactory;
import net.sf.hajdbc.util.Strings;

/**
 * Database-independent synchronization strategy that only updates differences between two databases.
 * This strategy is best used when there are <em>few</em> differences between the active database and the inactive database (i.e. barely out of sync).
 * The following algorithm is used:
 * <ol>
 *  <li>Drop the foreign keys on the inactive database (to avoid integrity constraint violations)</li>
 *  <li>For each database table:
 *   <ol>
 *    <li>Drop the unique constraints on the table (to avoid integrity constraint violations)</li>
 *    <li>Find the primary key(s) of the table</li>
 *    <li>Query all rows in the inactive database table, sorting by the primary key(s)</li>
 *    <li>Query all rows on the active database table</li>
 *    <li>For each row in table:
 *     <ol>
 *      <li>If primary key of the rows are the same, determine whether or not row needs to be updated</li>
 *      <li>Otherwise, determine whether row should be deleted, or a new row is to be inserted</li>
 *     </ol>
 *    </li>
 *    <li>Re-create the unique constraints on the table (to avoid integrity constraint violations)</li>
 *   </ol>
 *  </li>
 *  <li>Re-create the foreign keys on the inactive database</li>
 *  <li>Synchronize sequences</li>
 * </ol>
 * @author  Paul Ferraro
 */
public class DifferentialSynchronizationStrategy implements SynchronizationStrategy, Serializable
{
	private static final long serialVersionUID = -2785092229503649831L;

	private static Logger logger = LoggerFactory.getLogger(DifferentialSynchronizationStrategy.class);

	private int fetchSize = 0;
	private int maxBatchSize = 100;
	private Pattern versionPattern = null;
	
	/**
	 * @see net.sf.hajdbc.SynchronizationStrategy#synchronize(net.sf.hajdbc.SynchronizationContext)
	 */
	@Override
	public <P, D extends Database<P>> void synchronize(SynchronizationContext<P, D> context) throws SQLException
	{
		Connection sourceConnection = context.getConnection(context.getSourceDatabase());
		Connection targetConnection = context.getConnection(context.getTargetDatabase());

		Dialect dialect = context.getDialect();
		ExecutorService executor = context.getExecutor();
		
		boolean sourceAutoCommit = sourceConnection.getAutoCommit();
		boolean targetAutoCommit = targetConnection.getAutoCommit();
		
		targetConnection.setAutoCommit(true);
		
		SynchronizationSupport.dropForeignKeys(context);
		SynchronizationSupport.dropUniqueConstraints(context);
		
		sourceConnection.setAutoCommit(false);
		targetConnection.setAutoCommit(false);
		
		try
		{
			for (TableProperties table: context.getSourceDatabaseProperties().getTables())
			{
				String tableName = table.getName();
				
				UniqueConstraint primaryKey = table.getPrimaryKey();
				
				if (primaryKey == null)
				{
					throw new SQLException(Messages.PRIMARY_KEY_REQUIRED.getMessage(this.getClass().getName(), tableName));
				}
				
				List<String> primaryKeyColumnList = primaryKey.getColumnList();
				
				Collection<String> columns = table.getColumns();
				
				List<String> versionColumnList = new ArrayList<String>(1);
				List<String> nonPrimaryKeyColumnList = new ArrayList<String>(columns.size());
				
				for (String column: columns)
				{
					if (!primaryKeyColumnList.contains(column))
					{
						// Try to find a version column
						if ((this.versionPattern != null) && this.versionPattern.matcher(column).matches())
						{
							versionColumnList.add(column);
						}
						
						nonPrimaryKeyColumnList.add(column);
					}
				}
				
				String version = (versionColumnList.size() == 1) ? versionColumnList.get(0) : null;
				
				// List of columns for select statement - starting with primary key
				List<String> columnList = new ArrayList<String>(columns.size());
				
				columnList.addAll(primaryKeyColumnList);

				if (version != null)
				{
					columnList.add(version);
				}
				else
				{
					columnList.addAll(nonPrimaryKeyColumnList);
				}
				
				String commaDelimitedColumns = Strings.join(columnList, Strings.PADDED_COMMA);
				
				// Retrieve table rows in primary key order
				final String selectSQL = String.format("SELECT %s FROM %s ORDER BY %s", commaDelimitedColumns, tableName, Strings.join(primaryKeyColumnList, Strings.PADDED_COMMA)); //$NON-NLS-1$
				
				final Statement targetStatement = targetConnection.createStatement();

				targetStatement.setFetchSize(this.fetchSize);
				
				logger.log(Level.DEBUG, selectSQL);
				
				Callable<ResultSet> callable = new Callable<ResultSet>()
				{
					public ResultSet call() throws SQLException
					{
						return targetStatement.executeQuery(selectSQL);
					}
				};
	
				Future<ResultSet> future = executor.submit(callable);
				
				Statement sourceStatement = sourceConnection.createStatement();
				sourceStatement.setFetchSize(this.fetchSize);
				
				ResultSet sourceResultSet = sourceStatement.executeQuery(selectSQL);

				ResultSet targetResultSet = future.get();
				
				String primaryKeyWhereClause = Strings.join(primaryKeyColumnList, " = ? AND ") + " = ?"; //$NON-NLS-1$
				
				// Construct DELETE SQL
				String deleteSQL = String.format("DELETE FROM %s WHERE %s", tableName, primaryKeyWhereClause);
				
				logger.log(Level.DEBUG, deleteSQL);
				
				PreparedStatement deleteStatement = targetConnection.prepareStatement(deleteSQL);
				
				// Construct INSERT SQL
				String insertSQL = String.format("INSERT INTO %s (%s) VALUES (%s)", tableName, commaDelimitedColumns, Strings.join(Collections.nCopies(columnList.size(), Strings.QUESTION), Strings.PADDED_COMMA)); //$NON-NLS-1$
				
				logger.log(Level.DEBUG, insertSQL);
				
				PreparedStatement insertStatement = targetConnection.prepareStatement(insertSQL);
				
				// Construct UPDATE SQL
				PreparedStatement updateStatement = null;
				
				if (!nonPrimaryKeyColumnList.isEmpty())
				{
					String updateSQL = String.format("UPDATE %s SET %s = ? WHERE %s", tableName, Strings.join(nonPrimaryKeyColumnList, " = ?, "), primaryKeyWhereClause); //$NON-NLS-1$
					
					logger.log(Level.DEBUG, updateSQL);
					
					updateStatement = targetConnection.prepareStatement(updateSQL);
				}
				
				boolean hasMoreSourceResults = sourceResultSet.next();
				boolean hasMoreTargetResults = targetResultSet.next();
				
				int insertCount = 0;
				int updateCount = 0;
				int deleteCount = 0;
				
				while (hasMoreSourceResults || hasMoreTargetResults)
				{
					int compare = 0;
					
					if (!hasMoreSourceResults)
					{
						compare = 1;
					}
					else if (!hasMoreTargetResults)
					{
						compare = -1;
					}
					else
					{
						for (int i = 1; i <= primaryKeyColumnList.size(); ++i)
						{
							Object sourceObject = sourceResultSet.getObject(i);
							Object targetObject = targetResultSet.getObject(i);
							
							// We assume that the primary keys column types are Comparable
							compare = this.compare(sourceObject, targetObject);
							
							if (compare != 0)
							{
								break;
							}
						}
					}
					
					if (compare > 0)
					{
						deleteStatement.clearParameters();
						
						for (int i = 1; i <= primaryKeyColumnList.size(); ++i)
						{
							int type = dialect.getColumnType(table.getColumnProperties(columnList.get(i - 1)));
							
							deleteStatement.setObject(i, targetResultSet.getObject(i), type);
						}
						
						deleteStatement.addBatch();
						
						deleteCount += 1;
						
						if ((deleteCount % this.maxBatchSize) == 0)
						{
							deleteStatement.executeBatch();
							deleteStatement.clearBatch();
						}
					}
					else if (compare < 0)
					{
						if (version == null)
						{
							insertStatement.clearParameters();

							for (int i = 1; i <= columnList.size(); ++i)
							{
								int type = dialect.getColumnType(table.getColumnProperties(columnList.get(i - 1)));

								Object object = SynchronizationSupport.getObject(sourceResultSet, i, type);
								
								if (sourceResultSet.wasNull())
								{
									insertStatement.setNull(i, type);
								}
								else
								{
									insertStatement.setObject(i, object, type);
								}
							}
							
							insertStatement.addBatch();
							
							insertCount += 1;
							
							if ((insertCount % this.maxBatchSize) == 0)
							{
								insertStatement.executeBatch();
								insertStatement.clearBatch();
							}
						}
						else
						{
							
						}
					}
					else if (updateStatement != null) // if (compare == 0)
					{
						updateStatement.clearParameters();
						
						boolean updated = false;
						
						for (int i = primaryKeyColumnList.size() + 1; i <= columnList.size(); ++i)
						{
							int type = dialect.getColumnType(table.getColumnProperties(columnList.get(i - 1)));
							
							Object sourceObject = SynchronizationSupport.getObject(sourceResultSet, i, type);
							Object targetObject = SynchronizationSupport.getObject(targetResultSet, i, type);
							
							int index = i - primaryKeyColumnList.size();
							
							if (sourceResultSet.wasNull())
							{
								updateStatement.setNull(index, type);
								
								updated |= !targetResultSet.wasNull();
							}
							else
							{
								updateStatement.setObject(index, sourceObject, type);
								
								updated |= targetResultSet.wasNull();
								updated |= !equals(sourceObject, targetObject);
							}
						}
						
						if (updated)
						{
							for (int i = 1; i <= primaryKeyColumnList.size(); ++i)
							{
								int type = dialect.getColumnType(table.getColumnProperties(columnList.get(i - 1)));
								
								updateStatement.setObject(i + nonPrimaryKeyColumnList.size(), targetResultSet.getObject(i), type);
							}
							
							updateStatement.addBatch();
							
							updateCount += 1;
							
							if ((updateCount % this.maxBatchSize) == 0)
							{
								updateStatement.executeBatch();
								updateStatement.clearBatch();
							}
						}
					}
					
					if (hasMoreSourceResults && (compare <= 0))
					{
						hasMoreSourceResults = sourceResultSet.next();
					}
					
					if (hasMoreTargetResults && (compare >= 0))
					{
						hasMoreTargetResults = targetResultSet.next();
					}
				}
				
				if ((deleteCount % this.maxBatchSize) > 0)
				{
					deleteStatement.executeBatch();
				}
				
				deleteStatement.close();
				
				if ((insertCount % this.maxBatchSize) > 0)
				{
					insertStatement.executeBatch();
				}
				
				insertStatement.close();
				
				if (updateStatement != null)
				{
					if ((updateCount % this.maxBatchSize) > 0)
					{
						updateStatement.executeBatch();
					}
					
					updateStatement.close();
				}
				
				targetStatement.close();
				sourceStatement.close();
				
				targetConnection.commit();
				
				logger.log(Level.INFO, Messages.INSERT_COUNT.getMessage(), insertCount, tableName);
				logger.log(Level.INFO, Messages.UPDATE_COUNT.getMessage(), updateCount, tableName);
				logger.log(Level.INFO, Messages.DELETE_COUNT.getMessage(), deleteCount, tableName);
			}
		}
		catch (ExecutionException e)
		{
			SynchronizationSupport.rollback(targetConnection);
			
			throw SQLExceptionFactory.getInstance().createException(e.getCause());
		}
		catch (InterruptedException e)
		{
			SynchronizationSupport.rollback(targetConnection);
			
			throw SQLExceptionFactory.getInstance().createException(e);
		}
		catch (SQLException e)
		{
			SynchronizationSupport.rollback(targetConnection);
			
			throw e;
		}
		
		targetConnection.setAutoCommit(true);
		
		SynchronizationSupport.restoreUniqueConstraints(context);
		SynchronizationSupport.restoreForeignKeys(context);
		
		SynchronizationSupport.synchronizeIdentityColumns(context);
		SynchronizationSupport.synchronizeSequences(context);
		
		sourceConnection.setAutoCommit(sourceAutoCommit);
		targetConnection.setAutoCommit(targetAutoCommit);
	}

	private boolean equals(Object object1, Object object2)
	{
		if ((object1 instanceof byte[]) && (object2 instanceof byte[]))
		{
			byte[] bytes1 = (byte[]) object1;
			byte[] bytes2 = (byte[]) object2;
			
			if (bytes1.length != bytes2.length)
			{
				return false;
			}
			
			return Arrays.equals(bytes1, bytes2);
		}
		
		return object1.equals(object2);
	}
	
	@SuppressWarnings("unchecked")
	private int compare(Object object1, Object object2)
	{
		return ((Comparable) object1).compareTo(object2);
	}

	/**
	 * @return the fetchSize.
	 */
	public int getFetchSize()
	{
		return this.fetchSize;
	}

	/**
	 * @param fetchSize the fetchSize to set.
	 */
	public void setFetchSize(int fetchSize)
	{
		this.fetchSize = fetchSize;
	}

	/**
	 * @return Returns the maxBatchSize.
	 */
	public int getMaxBatchSize()
	{
		return this.maxBatchSize;
	}

	/**
	 * @param maxBatchSize The maxBatchSize to set.
	 */
	public void setMaxBatchSize(int maxBatchSize)
	{
		this.maxBatchSize = maxBatchSize;
	}

	/**
	 * @return the timestampPattern
	 */
	public String getVersionPattern()
	{
		return this.versionPattern.pattern();
	}

	/**
	 * @param versionPattern the timestampPattern to set
	 */
	public void setVersionPattern(String pattern)
	{
		this.versionPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
	}
}
