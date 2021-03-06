package io.frjufvjn.lab.vertx_mybatis.mysqlBinlog;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Scopes;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;

import io.frjufvjn.lab.vertx_mybatis.Constants;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.LocalMap;

/**
 * 	@author PJW
 *	@description NOTE : Vertx's multi instance usage is not supported
 */
public class BinLogClientVerticle extends AbstractVerticle {

	private final Logger logger = LogManager.getLogger(BinLogClientVerticle.class);
	private JsonArray finalList = new JsonArray();
	private ConcurrentMap<String,String> tableMap = new ConcurrentHashMap<String,String>();
	private Injector services = null;
	LocalMap<String,JsonArray> pubsubServices = null;
	LocalMap<String,JsonObject> sessions = null;

	final private String BINLOG_SERVICE_CONFIG = "config/pubsub-mysql-service.json";
	final private String EB_MSG_KEY_WS = "msg.mysql.live.select";

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		/**
		 * @description User Session Map From Websocket
		 * */
		sessions = vertx.sharedData().getLocalMap(Constants.WEBSOCKET_CHANNEL);



		/**
		 * @description bin-log service configuration Read
		 * */
		vertx.fileSystem().readFile(BINLOG_SERVICE_CONFIG, f -> {
			if ( f.succeeded() ) {
				pubsubServices = vertx.sharedData().getLocalMap("ws.pubsubServices");
				pubsubServices.put("table", new JsonArray(f.result().getString(0, f.result().length(), "UTF-8")));
				logger.info("bin-log service config Read Complete!!");
			}
		});



		/**
		 * @description MySQL information_schema data service
		 * */
		services = Guice.createInjector(new AbstractModule() {
			@Override
			protected void configure() {
				bind(SchemaService.class).in(Scopes.SINGLETON);
			}
		});

		services.getInstance(SchemaService.class).loadSchemaData(config().getString("schema"));



		/**
		 * @description bin-log client connect, listen
		 * */
		BinaryLogClient client = new BinaryLogClient(
				config().getString("url"),
				config().getInteger("port"),
				config().getString("schema"),
				config().getString("username"),
				config().getString("local-mysql-password"));

		client.registerEventListener(evt -> {
			if ( logger.isDebugEnabled() ) {
				logger.debug("\n- evt type : {}\n- HeaderInfo:[{}]\n- DataInfo:[{}]\n", 
						evt.getHeader().getEventType(), evt.getHeader().toString(), evt.getData().toString());
			}

			dispatch(evt.getHeader().getEventType(), evt.getData());
		});

		client.connect(1000);
	}

	/**
	 * @description Event Dispatcher
	 * @param type
	 * @param data
	 * @throws Exception
	 */
	private void dispatch(EventType type, Object data) {

		switch (BinlogEventType.getEvtType(type)) {
		case QUERY :
			String sql = ((QueryEventData) data).getSql().toUpperCase().trim();
			if (sql.startsWith("CREATE TABLE") ||
					sql.startsWith("DROP TABLE") ||
					sql.startsWith("ALTER TABLE"))
			{
				logger.info("Handle DDL statement, clear column mapping");
				services.getInstance(SchemaService.class).loadSchemaData(config().getString("schema"));
			}
			break;

			// Throttling by Transaction Row Range
		case XID :
			logger.info("- DML Transaction final result -");

			// List of registered services configuration
			pubsubServices.get("table").forEach(svc -> {
				long count = finalList.stream()
						.filter(item -> ((JsonObject) svc).getString("trigger-table").equals(((JsonObject) item).getString("table")) )
						.count();

				if ( count > 0 && sessions.size() > 0) {
					String sqlName = ((JsonObject) svc).getString("bind-sql-id");
					if (logger.isDebugEnabled()) {
						logger.debug("execute service : {}, sql : {}", ((JsonObject) svc).getString("service-name"), sqlName);
					}

					EventBus eb = vertx.eventBus();

					// Execute Query with sql registered in service configuration.
					eb.send(Constants.EVENTBUS_SQL_VERTICLE_ADDR, new JsonObject().put("sqlName", sqlName) , reply -> {
						if (reply.succeeded()) {
							// Send the query result to the client registered in websocket.
							sessions.forEach((key,sessionObj) -> {
								if (sessionObj.getValue("service-name").equals(((JsonObject) svc).getString("service-name"))) {
									logger.info("send data, subscription key : " + key);
									eb.send(EB_MSG_KEY_WS, new JsonObject().put("user-key", key).put("data", reply.result().body().toString()));
								}
							});
						}
						// TODO Refactoring >> Under any circumstances, clear must be executed. !!!
						finalList.clear();
					});
				}
			});
			break;

		case TABLE_MAP :
			tableMap.clear();
			tableMap.put("schema", ((TableMapEventData) data).getDatabase());
			tableMap.put("table", ((TableMapEventData) data).getTable());
			break;

		case WRITE :
			((WriteRowsEventData) data).getRows().forEach(row -> {
				handleRowEvent(tableMap.get("schema"), tableMap.get("table"), type, Arrays.asList(row));
			});
			break;

		case UPDATE :
			((UpdateRowsEventData) data).getRows().forEach(row -> {
				handleRowEvent(tableMap.get("schema"), tableMap.get("table"), type, Arrays.asList(row.getValue()));
			});
			break;

		case DELETE :
			((DeleteRowsEventData) data).getRows().forEach(row -> {
				handleRowEvent(tableMap.get("schema"), tableMap.get("table"), type, Arrays.asList(row));
			});
			break;
		default:
			break;
		}
	}

	/**
	 * @description handle row data
	 * @param schema
	 * @param table
	 * @param type
	 * @param fields
	 */
	private void handleRowEvent(String schema, String table, EventType type, List<Serializable> fields) {
		List<JsonObject> columns = services.getInstance(SchemaService.class).filterByTable(schema, table);

		Map<String, Object> row = IntStream
				.range(0, columns.size())
				.boxed()
				.collect(HashMap::new,
						(map, i) -> map.put(columns.get(i).getString("column_name"), fields.get(i)),
						HashMap::putAll);

		finalList.add(new JsonObject()
				.put("schema", schema)
				.put("table", table)
				.put("type", BinlogEventType.getEvtType(type).getEvtName())
				.put("row", new JsonObject(row)));
	}
}
