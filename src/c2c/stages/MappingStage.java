package c2c.stages;

import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import c2c.api.*;
import c2c.payloads.IntermediateKeyValue;
import c2c.payloads.JobStatus;
import c2c.payloads.KeyPayload;
import c2c.payloads.KeyValue;
import c2c.payloads.Value;
import c2c.utilities.MapReduceStage;
import c2c.utilities.RemoteJob;

import seda.sandStorm.api.*;
import bamboo.api.*;
import bamboo.dht.Dht;
import bamboo.dht.Dht.PutResp;
import bamboo.dht.bamboo_stat;

public final class MappingStage extends MapReduceStage {

	// Here KeyPayload corresponds to a mapper key
	private final Map<KeyPayload, Integer> remaining = new HashMap<KeyPayload, Integer>();

	private final Map<String, RemoteJob> jobs = new HashMap<String, RemoteJob>();
	
	private final ExecutorService pool = Executors.newCachedThreadPool();

	public static final long app_id = bamboo.router.Router
			.app_id(MappingStage.class);

	public MappingStage() throws Exception {
		super(Dht.PutResp.class);
	}

	@Override
	protected void handleOperationalEvent(QueueElementIF event) {
		if (event instanceof BambooRouteDeliver) { // do the computation
			handleMapRequest((BambooRouteDeliver) event);
		} else if (event instanceof Dht.PutResp) {
			handlePutResp((PutResp) event);
		} else {
			BUG("Event " + event + " unknown.");
		}
	}

	private RemoteJob getJob(String domain, BigInteger master) {
		if (!jobs.containsKey(domain)) {
			try {
				jobs.put(domain, new RemoteJob(domain, master));
			} catch (Exception e) {
				BUG(e);
			}
		}
		return jobs.get(domain);
	}

	private void handleMapRequest(final BambooRouteDeliver event) {
		final KeyValue kv = (KeyValue) event.payload;
		final RemoteJob job = getJob(kv.key.domain, event.src);

		logger.info("Mapping " + kv.key);

		// The user's map function may be blocking so start a new thread.
		pool.execute(new Runnable() {
			boolean working = true;
			
			@Override
			public void run() {
				final Collector c = new Collector(kv.key);
				
				// Notify the master that we're now working
				acore.registerTimer(10, new Runnable() {
					@Override
					public void run() {
						if (working) {
							dispatchTo(event.src, MasterStage.app_id,
									new JobStatus(kv.key, JobStatus.STARTED, true));
							acore.registerTimer(1000, this);
						}
					}
				});
				
				job.getMapper().map(kv.key.data, kv.value, c);

				// Get back to main thread
				acore.registerTimer(0, new Runnable() {

					@Override
					public void run() {
						working = false;
						c.flush();

						// Tell the master that we're done
						dispatchTo(event.src, MasterStage.app_id, new JobStatus(kv.key, JobStatus.FINISHED, true));
					}
				});
			}
		});
	}

	private void handlePutResp(Dht.PutResp response) {
		IntermediateKeyValue kv = (IntermediateKeyValue) response.user_data;
		if (response.result == bamboo_stat.BAMBOO_OK) {
			remaining.put(kv.creator, remaining.get(kv.creator) - 1);
			if (remaining.get(kv.creator) == 0) {
				dispatchTo(jobs.get(kv.key.domain).getMaster(), MasterStage.app_id, new JobStatus(kv.creator, JobStatus.PERSISTED, true));
			}
		} else {
			logger.warn("Retrying put for " + kv + ". System may be over capacity.");
			doPut(kv);
		}
	}

	private void doPut(IntermediateKeyValue kv) {
		Dht.PutReq req = new Dht.PutReq(kv.key.toNode(), kv.value.toByteBuffer(),
				kv.value.hash(), true, my_sink, kv, 600,
				my_node_id.address());
		classifier.dispatch_later(req, 500);
	}

	@Override
	public long getAppID() {
		return app_id;
	}

	private class Collector implements OutputCollector {
		private final KeyPayload mapping_key; // the original input key for this mapping

		private Set<String> keys = new HashSet<String>();
		private Collection<KeyValue> keyvalues = new LinkedList<KeyValue>();

		public Collector(KeyPayload mapping_key) {
			this.mapping_key = mapping_key;
			assert mapping_key != null;
		}

		public void flush() {
			remaining.put(mapping_key, keys.size() + keyvalues.size());
			KeyPayload inter = KeyPayload.intermediateKeys(mapping_key.domain);
			for (String key : keys) {
				makePut(inter, key, false);
			}
			for (KeyValue kv : keyvalues) {
				makePut(kv.key, kv.value, true);
			}
		}

		@Override
		public void collect(String key, String value) {
			keys.add(key);
			keyvalues.add(new KeyValue(new KeyPayload(mapping_key.domain, key), value));
		}

		private void makePut(KeyPayload key, String value, boolean allow_duplicates) {
			Value val = new Value(value, allow_duplicates);
			IntermediateKeyValue ud = new IntermediateKeyValue(mapping_key, key, val);
			Dht.PutReq req = new Dht.PutReq(key.toNode(), val.toByteBuffer(),
					val.hash(), true, my_sink, ud, 600,
					my_node_id.address());
			dispatch(req);
		}
	}

}
