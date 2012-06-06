package com.thinkaurelius.titan.diskstorage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thinkaurelius.titan.diskstorage.writeaggregation.MultiWriteKeyColumnValueStore;
import com.thinkaurelius.titan.diskstorage.writeaggregation.Mutation;

public abstract class MultiWriteKeyColumnValueStoreTest {
	
	private Logger log = LoggerFactory.getLogger(MultiWriteKeyColumnValueStoreTest.class);

	int numKeys = 500;
	int numColumns = 50;

    protected String storeName = "testStore1";
	
	public StorageManager manager;
	public TransactionHandle tx;
	public MultiWriteKeyColumnValueStore store;
	
	@Before
	public void setUp() throws Exception {
		cleanUp();
        open();
	}

    public abstract void cleanUp();

    public abstract StorageManager openStorageManager();

	public void open() {
        manager = openStorageManager();
        tx = manager.beginTransaction();
        store = (MultiWriteKeyColumnValueStore)manager.openDatabase(storeName);
    }
    
    public void clopen() {
        close();
        open();
    }
	
	@After
	public void tearDown() throws Exception {
		close();
	}

    public void close() {
        if (tx!=null) tx.commit();
        if (null != store) ((KeyColumnValueStore)store).close();
        if (null != manager) manager.close();
    }
    
    @Test
    public void deletionsAppliedBeforeAdditions() {
    	
    	ByteBuffer b1 = ByteBuffer.allocate(1);
    	b1.put((byte)1).rewind();
    	
    	assertNull("The store's initial state is not empty",
    			((KeyColumnValueStore)store).get(b1, b1, tx));
    	
    	List<Entry> additions = Arrays.asList(new Entry(b1, b1));
    	
    	List<ByteBuffer> deletions = Arrays.asList(b1);
    	
    	Map<ByteBuffer, Mutation> combination = new HashMap<ByteBuffer, Mutation>(1);
    	Map<ByteBuffer, Mutation> deleteOnly = new HashMap<ByteBuffer, Mutation>(1);
    	Map<ByteBuffer, Mutation> addOnly = new HashMap<ByteBuffer, Mutation>(1);
    	
    	combination.put(b1, new Mutation(additions, deletions));
    	deleteOnly.put(b1, new Mutation(null, deletions));
    	addOnly.put(b1, new Mutation(additions, null));
    	
    	store.mutateMany(combination, tx);
    	
    	ByteBuffer result = ((KeyColumnValueStore)store).get(b1, b1, tx);
    	
    	assertEquals(b1, result);
    	
    	for (int i = 0; i < 100; i++) {
    		store.mutateMany(addOnly, tx);
    		store.mutateMany(deleteOnly, tx);
    		assertNull(((KeyColumnValueStore)store).get(b1, b1, tx));
    	}
    	
    	for (int i = 0; i < 100; i++) {
    		store.mutateMany(deleteOnly, tx);
    		store.mutateMany(addOnly, tx);
    		assertEquals(b1, ((KeyColumnValueStore)store).get(b1, b1, tx));
    	}
    	
    	for (int i = 0; i < 100; i++) {
    		store.mutateMany(combination, tx);
    		assertEquals(b1, ((KeyColumnValueStore)store).get(b1, b1, tx));
    	}
    }
    
    @Test
    public void mutateManyStressTest() {
    
    	Map<ByteBuffer, Map<ByteBuffer, ByteBuffer>> state =
    			new HashMap<ByteBuffer, Map<ByteBuffer, ByteBuffer>>();
    	
    	
    	for (int round = 0; round < 10; round++) {
    		Map<ByteBuffer, Mutation> changes = mutateState(state, 4096);
    	
    		store.mutateMany(changes, tx);
    	
    		checkThatStateExistsInStore(state, (KeyColumnValueStore)store, round);
    		checkThatDeletionsApplied(changes, (KeyColumnValueStore)store, round);
    	}
    }
    
    public void checkThatStateExistsInStore(Map<ByteBuffer, Map<ByteBuffer, ByteBuffer>> state, KeyColumnValueStore store, int round) {
    	int checked = 0;
    	
    	for (ByteBuffer key : state.keySet()) {
    		for (ByteBuffer col : state.get(key).keySet()) {
    			ByteBuffer val = state.get(key).get(col);
    			
    			assertEquals(val, store.get(key, col, tx));
    			
    			checked++;
    		}
    	}
    	
    	log.debug("Checked existence of {} key-column-value triples on round {}", checked, round);
    }
    
    public void checkThatDeletionsApplied(Map<ByteBuffer, Mutation> changes, KeyColumnValueStore store, int round) {
    	int checked = 0;
    	
    	for (ByteBuffer key : changes.keySet()) {
    		Mutation m = changes.get(key);
    		
    		if (!m.hasDeletions())
    			continue;
    		
    		List<ByteBuffer> deletions = m.getDeletions();
    		
    		List<Entry> additions = m.getAdditions();
    		
    		for (ByteBuffer col : deletions) {
    			
    			if (null != additions && additions.contains(new Entry(col, col))) {
    				continue;
    			}
    			
    			assertNull(store.get(key, col, tx));
    			
    			checked++;
    		}
    	}
    	
    	log.debug("Checked absence of {} key-column-value deletions on round {}", checked, round);
    }
    
    public Map<ByteBuffer, Mutation> mutateState(Map<ByteBuffer, Map<ByteBuffer, ByteBuffer>> state, int mutationCount) {
    	Random rand = new Random(10);

    	final int keyLength = 8;
    	final int colLength = 6;
    	
    	Map<ByteBuffer, Mutation> result = new HashMap<ByteBuffer, Mutation>();
    	
    	Map<ByteBuffer, Set<ByteBuffer>> delta = new
    			HashMap<ByteBuffer, Set<ByteBuffer>>();
    	
    	for (int i = 0; i < mutationCount; i++) {
    		if (3 > rand.nextInt() % 4) {
    			// addition
    			
    			ByteBuffer key, col;
    			
    			while (true) {
    	    		byte keyBuf[] = new byte[keyLength];
    	    		rand.nextBytes(keyBuf);
    	    		key = ByteBuffer.wrap(keyBuf);
    	    		
        			byte colBuf[] = new byte[colLength];
        			rand.nextBytes(colBuf);
        			col = ByteBuffer.wrap(colBuf);
        			
        			if (!state.containsKey(key) || !state.get(key).containsKey(col)) {
        				break;
        			}
    			}
        			
        		if (!state.containsKey(key)) {
        			Map<ByteBuffer, ByteBuffer> m = new HashMap<ByteBuffer, ByteBuffer>();
        			state.put(key, m);
        		}
        			
        		state.get(key).put(col, col);
        		
        		if (!delta.containsKey(key)) {
        			delta.put(key, new HashSet<ByteBuffer>());
        		}
        		
        		delta.get(key).add(col);
        		
        		if (!result.containsKey(key)) {
        			Mutation m = new Mutation(new LinkedList<Entry>(), new LinkedList<ByteBuffer>());
        			result.put(key, m);
        		}
        			
        		result.get(key).getAdditions().add(new Entry(col, col));
        		
    		} else {
    			// deletion
    			
    			ByteBuffer key, col;

    			Iterator<Map.Entry<ByteBuffer, Map<ByteBuffer, ByteBuffer>>> keyIter =
    					state.entrySet().iterator();
    			
    			if (!keyIter.hasNext())
    				continue;
    			
    			Map.Entry<ByteBuffer, Map<ByteBuffer, ByteBuffer>> target = keyIter.next();
    			
    			key = target.getKey();
    			
    			Iterator<ByteBuffer> colIter = target.getValue().values().iterator();
    			
    			if (!colIter.hasNext())
    				continue;
    			
    			col = colIter.next();

    			if (delta.containsKey(key) && delta.get(key).contains(col)) {
    				// just skip the whole deletion
    				// this can put us under mutationCount
    				continue;
    			}
    			
        		if (!result.containsKey(key)) {
        			Mutation m = new Mutation(new LinkedList<Entry>(), new LinkedList<ByteBuffer>());
        			result.put(key, m);
        		}
        		
        		result.get(key).getDeletions().add(col);

    			state.get(key).remove(col);
    			
    			if (state.get(key).isEmpty()) {
    				state.remove(key);
    			}
    		}
    	}
    	
    	return result;
    }
    
    public Map<ByteBuffer, Mutation> generateMutation(int keyCount, int columnCount, Map<ByteBuffer, Mutation> deleteFrom) {
    	Map<ByteBuffer, Mutation> result = new HashMap<ByteBuffer, Mutation>(keyCount);
    	
    	Random keyRand = new Random(keyCount);
    	Random colRand = new Random(columnCount);
    	
    	final int keyLength = 8;
    	final int colLength = 6;
    	
    	Iterator<Map.Entry<ByteBuffer, Mutation>> deleteIter = null;
    	List<Entry> lastDeleteIterResult = null;
    	
    	if (null != deleteFrom) {
    		deleteIter = deleteFrom.entrySet().iterator();
    	}
    	
    	for (int ik = 0; ik < keyCount; ik++) {
    		byte keyBuf[] = new byte[keyLength];
    		keyRand.nextBytes(keyBuf);
    		ByteBuffer key = ByteBuffer.wrap(keyBuf);
    		
    		List<Entry> additions = new LinkedList<Entry>();
    		List<ByteBuffer> deletions = new LinkedList<ByteBuffer>();
    		
    		for (int ic = 0; ic < columnCount; ic++) {
    			
    			boolean deleteSucceeded = false;
    			if (null != deleteIter && 1 == ic % 2) {

					if (null == lastDeleteIterResult || lastDeleteIterResult.isEmpty()) {
						while (deleteIter.hasNext()) {
        					Map.Entry<ByteBuffer, Mutation> ent = deleteIter.next();
        					if (ent.getValue().hasAdditions() && !ent.getValue().getAdditions().isEmpty()) {
        						lastDeleteIterResult = ent.getValue().getAdditions();
        						break;
        					}
    					}
    				}

					
					if (null != lastDeleteIterResult && !lastDeleteIterResult.isEmpty()) {
						Entry e = lastDeleteIterResult.get(0);
						lastDeleteIterResult.remove(0);
						deletions.add(e.getColumn());
						deleteSucceeded = true;
					}
    			}
    			
    			if (!deleteSucceeded) {
        			byte colBuf[] = new byte[colLength];
        			colRand.nextBytes(colBuf);
        			ByteBuffer col = ByteBuffer.wrap(colBuf);
        			
        			additions.add(new Entry(col, col));
    			}
    			
    		}
    		
    		Mutation m = new Mutation(additions, deletions);
    		
    		result.put(key, m);
    	}
    	
    	return result;
    }
}
