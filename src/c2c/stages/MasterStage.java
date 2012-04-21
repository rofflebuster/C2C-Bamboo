package c2c.stages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import seda.sandStorm.api.QueueElementIF;

import c2c.events.*;
import c2c.payloads.*;
import c2c.utilities.WorkerTable;

import bamboo.api.*;

/**
 * Takes job requests from a Client and disperses them to mappers.
 * 
 * @author Caleb Perkins
 * 
 */
public final class MasterStage extends MapReduceStage {
	public static final long app_id = bamboo.router.Router
			.app_id(MasterStage.class);
	
	private final Map<String, Integer> expected = new HashMap<String, Integer>();
	private final Map<String, Set<String>> completed = new HashMap<String, Set<String>>();
	
	// KV's have to be stored in case we need to reissue a job
	private Map<String, KeyValue> jobs = new HashMap<String, KeyValue>();
	// When was the last time we heard from a worker?
	private WorkerTable workers = new WorkerTable();

	public MasterStage() throws Exception {
		super(KeyValue.class, JobRequest.class, ReducingUnderway.class);
		ostore.util.TypeTable.register_type(KeyPayload.class);
	}
	
	private void handleReducerDone(KeyPayload k) {
		logger.debug("Reducer done for " + k);
		Set<String> comp = completed.get(k.domain);
		comp.add(k.data);
		if (comp.size() == expected.get(k.domain)) {
			dispatch(new JobDone(k.domain));
		}
	}
	
	private void handleResultBack(KeyValue kv) {
		logger.debug("Result back: " + kv);
		dispatch(kv);
	}
	
	private void handleReducingUnderway(ReducingUnderway event) {
		expected.put(event.domain, event.reducers);
		completed.put(event.domain, new HashSet<String>());
	}

	@Override
	protected void handleOperationalEvent(QueueElementIF event) {
		if (event instanceof BambooRouteDeliver) { // get back the results
			BambooRouteDeliver deliver = (BambooRouteDeliver) event;
			if (deliver.payload instanceof KeyValue) {
				handleResultBack((KeyValue) deliver.payload);
			} else if (deliver.payload instanceof KeyPayload) {
				handleReducerDone((KeyPayload) deliver.payload);
			} else {
				BUG("Unknown payload.");
			}	
		} else if (event instanceof JobRequest) { // Distribute jobs to mappers.
			JobRequest req = (JobRequest) event;
			dispatch(new MappingUnderway(req.domain, req.pairs.size()));
			for (KeyValue pair : req.pairs) {
				workers.addJob(pair.key.domain);
				jobs.put(pair.key.domain, pair);
				
				// Distribute to different nodes.
				dispatchTo(pair.key.toNode(), MappingStage.app_id,
						pair);
			}
			
			// Schedule rescan of worker table
			acore.register_timer(4500, rescanTable);
		} else if (event instanceof MappingUnderway) {
			// Update job in table - it's not dead!
			workers.addJob(((MappingUnderway)event).domain);
		} else if (event instanceof JobDone) {
			// A mapper is finished - remove it from table and stop checking
			String done = ((JobDone)event).domain;
			workers.removeJob(done);
			jobs.remove(done);
		} else if (event instanceof ReducingUnderway) {
			handleReducingUnderway((ReducingUnderway) event);
		} else {
			BUG("Event " + event + " unknown.");
		}
	}
	
	private Runnable rescanTable = new Runnable() {
		public void run() {
			// Redispatch all failed jobs 
			for (String failed : workers.scan()) {
				KeyValue pair = jobs.get(failed);
				assert(failed.equals(pair.key.domain));
				
				// Readd as current
				workers.addJob(failed);
				dispatchTo(pair.key.toNode(), MappingStage.app_id,
						pair);
			}
			
			// Schedule next rescan
			acore.registerTimer(4500, rescanTable);
		}
	};

	@Override
	public long getAppID() {
		return app_id;
	}

}
