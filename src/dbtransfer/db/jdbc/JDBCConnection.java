/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package dbtransfer.db.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import pt.evolute.arrays.LightResultSet2DArray;
import pt.evolute.arrays.Virtual2DArray;
import pt.evolute.db.Connector;
import pt.evolute.dbmodel.DBTable;
import pt.evolute.dbmodel.ModelProvider;
import dbtransfer.db.DBConnection;
import dbtransfer.db.beans.ColumnDefinition;
import dbtransfer.db.beans.ForeignKeyDefinition;
import dbtransfer.db.beans.Name;
import dbtransfer.db.beans.PrimaryKeyDefinition;
import dbtransfer.db.beans.UniqueDefinition;
import dbtransfer.db.helper.Helper;
import dbtransfer.db.helper.HelperManager;
import pt.evolute.arrays.CursorResultSet2DArray;

/**
 *
 * @author lflores
 */
public class JDBCConnection implements DBConnection
{
	public static boolean debug = false;
	
	private final Connection connection;

	private final String catalog;
	private final String schema;
	private final boolean ignoreEmpty;
	
	private final Helper helper;

	public JDBCConnection( String url, String user, String pass, boolean onlyNotEmpty )
			throws Exception
	{
		connection = Connector.getConnection( url, user, pass );
		catalog = connection.getCatalog();
		schema = Connector.getSchema( url );
		ignoreEmpty = onlyNotEmpty;
		helper = HelperManager.getTranslator( url );
		System.out.println( "JDBC: " + url + " catalog: " + catalog + " schema: " + schema );
	}

	public List<Name> getTableList()
			throws Exception
	{
		DatabaseMetaData rsmd = connection.getMetaData();
		ResultSet rs = rsmd.getTables( catalog, schema, null, new String[] { "TABLE" } );
		List<Name> v = new LinkedList<Name>();
		while( rs.next() )
		{
			String table = rs.getString( 3 );
			Name n = new Name( table );
			if( ignoreEmpty && getRowCount( n ) == 0 )
			{
				continue;
			}
			v.add( n );
		}
		rs.close();
		return v;
	}

	public List<ColumnDefinition> getColumnList( Name table ) throws Exception
	{
		DatabaseMetaData rsmd = connection.getMetaData();
		ResultSet rs = rsmd.getColumns( catalog, schema, table.originalName, null );
		List<ColumnDefinition> list = new LinkedList<ColumnDefinition>();
		Map<String,ColumnDefinition> cols = new HashMap<String, ColumnDefinition>();
//		boolean quit = false;
		while( rs.next() )
		{
			Name name = new Name( rs.getString( 4 ) );
//			String name = StringPlainer.convertString( rs.getString( 4 ) );
//			if( "nag_calendario".equalsIgnoreCase( table ) )
//			{
//				System.out.println( "T: " + table + " <" + rs.getString( 4 ) + ">  PLAIN: <" + name + ">" );
//				quit = true;
//			}
//			else
//			{
//				if( quit )
//				{
//					System.exit( 0 );
//				}
//			}
			if( !cols.containsKey( name.saneName ) )
			{
				ColumnDefinition col = new ColumnDefinition();
				col.name = name;
				col.sqlTypeName = rs.getString( 6 );
				col.sqlType = rs.getInt( 5 );
				if( rs.getInt( 5 ) == Types.CHAR
						|| rs.getInt( 5 ) == Types.LONGVARCHAR
						|| rs.getInt( 5 ) == Types.VARCHAR
						|| rs.getInt( 5 ) == Types.NCHAR
						|| rs.getInt( 5 ) == Types.LONGNVARCHAR
						|| rs.getInt( 5 ) == Types.NVARCHAR )
				{
					col.sqlSize = rs.getInt( 7 );
				}
				col.defaultValue = helper.normalizeDefault( rs.getString( 13 ) );
				col.isNotNull = "NO".equals( rs.getString( 18 ).trim() );
				cols.put( col.name.saneName, col );
				list.add( col );
			}
			else
			{
				System.out.println( "Ignoring duplicate: " + table.originalName + " - " + name );
				new Exception( "Ignoring duplicate: " + table.originalName + " - " + name ).printStackTrace();;
			}
		}
		rs.close();
		return list;
	}

	public Virtual2DArray executeQuery(String sql) throws Exception
	{
		if( debug )
		{
			System.out.println( "SQL: " + sql );
		}
		Statement stm = connection.createStatement( ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY );
		boolean hasResult = stm.execute( sql );
		Virtual2DArray ret = null;
		if( hasResult )
		{
			ResultSet rs = stm.getResultSet();
			ret = new CursorResultSet2DArray( rs );
		}
		return ret;
	}

	public PrimaryKeyDefinition getPrimaryKey( Name table) throws Exception
	{
		PrimaryKeyDefinition pk = new PrimaryKeyDefinition();
		pk.name = table + "_pk";
		DatabaseMetaData rsmd = connection.getMetaData();
		ResultSet rs = rsmd.getPrimaryKeys( catalog, schema, table.originalName );
		while( rs.next() )
		{
			ColumnDefinition col = new ColumnDefinition();
			col.name = new Name( rs.getString( 4 ) );
			ResultSet rsC = rsmd.getColumns( catalog, schema, table.originalName, col.name.originalName );
			rsC.next();
			col.sqlTypeName = rsC.getString( 6 );
			if( rsC.getInt( 5 ) == Types.CHAR
					|| rsC.getInt( 5 ) == Types.LONGVARCHAR
					|| rsC.getInt( 5 ) == Types.VARCHAR
					|| rsC.getInt( 5 ) == Types.NCHAR
					|| rsC.getInt( 5 ) == Types.LONGNVARCHAR
					|| rsC.getInt( 5 ) == Types.NVARCHAR )
			{
				col.sqlSize = rsC.getInt( 7 );
			}
			col.defaultValue = rsC.getString( 13 );
			col.isNotNull = "NO".equals( rsC.getString( 18 ).trim() );
			rsC.close();
			pk.columns.add( col );
		}
		rs.close();
		return pk;
	}

	public List<ForeignKeyDefinition> getForeignKeyList(Name table) throws Exception
	{
		DatabaseMetaData rsmd = connection.getMetaData();
		System.out.println( "getting FKs: " + connection.getCatalog() + "/" + schema + "/" + table.originalName + "/" );
		ResultSet rs = rsmd.getImportedKeys( catalog, schema, table.originalName );
		List<ForeignKeyDefinition> list = new LinkedList<ForeignKeyDefinition>();
		Map<String,ForeignKeyDefinition> fks = new HashMap<String, ForeignKeyDefinition>();
		while( rs.next() )
		{
			String fkName = rs.getString( 12 );
System.out.println( "FK : " + fkName );
			ForeignKeyDefinition fk = fks.get( fkName );
			if( fk == null )
			{
				fk = new ForeignKeyDefinition( fkName, table );
				fks.put( fkName, fk );
			}
			ColumnDefinition col = new ColumnDefinition();
			col.referencedTable = new Name( rs.getString( 3 ) );
			col.referencedColumn = new Name( rs.getString( 4 ) );
			col.name = new Name( rs.getString( 8 ) );
			ResultSet rsC = rsmd.getColumns( catalog, schema, table.originalName, col.name.originalName );
			if( rsC.next() )
			{
				col.sqlTypeName = rsC.getString( 6 );
				if( rsC.getInt( 5 ) == Types.CHAR
						|| rsC.getInt( 5 ) == Types.LONGVARCHAR
						|| rsC.getInt( 5 ) == Types.VARCHAR
						|| rsC.getInt( 5 ) == Types.NCHAR
						|| rsC.getInt( 5 ) == Types.LONGNVARCHAR
						|| rsC.getInt( 5 ) == Types.NVARCHAR )
				{
					col.sqlSize = rsC.getInt( 7 );
				}
				col.defaultValue = rsC.getString( 13 );
				col.isNotNull = "NO".equals( rsC.getString( 18 ).trim() );
				rsC.close();
			}
			else
			{
				new Exception( "Can't find column for fk - " + fk.name + "/ col " + col.name ).printStackTrace();
			}
			fk.columns.add( col );
			list.add( fk );
		}
		rs.close();
		return list;
	}

	public Virtual2DArray getFullTable( Name table ) throws Exception
	{
		List<ColumnDefinition> cols = getColumnList( table );
		StringBuilder buffer = new StringBuilder( cols.remove( 0 ).name.originalName );
		for( ColumnDefinition col: cols )
		{
			buffer.append( ", " );
			buffer.append( col.name.originalName );
		}
		return executeQuery( "SELECT " + buffer + " FROM " + table.originalName );
	}

	public PreparedStatement prepareStatement(String sql) throws Exception
	{
		return connection.prepareStatement( sql );
	}
	
	@Override
	public List<DBTable> getSortedTables() throws Exception 
	{
		ModelProvider model = new ModelProvider( connection );
		return model.getSortedTables();
	}
	
	@Override
	public List<UniqueDefinition> getUniqueList( Name table )
		throws Exception
	{
		DatabaseMetaData rsmd = connection.getMetaData();
		ResultSet rs = rsmd.getIndexInfo( catalog, schema, table.originalName, true, false );
		List<UniqueDefinition> list = new LinkedList<UniqueDefinition>();
		UniqueDefinition lastUniq = null;
		while( rs.next() )
		{
			if( rs.getString( 6 ) != null && rs.getString( 9 ) != null )
			{
				if( lastUniq == null || !lastUniq.name.equals( rs.getString( 6 ) ) )
				{
					lastUniq = new UniqueDefinition( rs.getString( 6 ), table );
					list.add( lastUniq );
				}
				lastUniq.columns.add( rs.getString( 9 ) );
			}
			else
			{
				System.out.println( "Discarding index: " + rs.getString( 6 ) );
			}
		}
		rs.close();
		return list;
	}

	@Override
	public int getRowCount( Name table) throws Exception 
	{
		return ( ( Number )executeQuery( "SELECT COUNT(*) FROM \"" + table.originalName + "\"" ).get( 0, 0 ) ).intValue();
	}
}
