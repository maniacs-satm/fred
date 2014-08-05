/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.client.FetchContext;
import freenet.keys.ClientKey;
import freenet.keys.ClientKeyBlock;
import freenet.keys.ClientSSK;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.NullSendableRequestItem;
import freenet.node.RequestClient;
import freenet.node.RequestScheduler;
import freenet.node.SendableGet;
import freenet.node.SendableRequestItem;
import freenet.support.Logger;
import freenet.support.TimeUtil;

public abstract class BaseSingleFileFetcher extends SendableGet implements HasKeyListener, HasCooldownTrackerItem {

	public static class MyCooldownTrackerItem implements CooldownTrackerItem {

		public int retryCount;
		public long cooldownWakeupTime;

	}

	final ClientKey key;
	protected boolean cancelled;
	protected boolean finished;
	final int maxRetries;
	private int retryCount;
	final FetchContext ctx;
	protected boolean deleteFetchContext;
	static final SendableRequestItem[] keys = new SendableRequestItem[] { NullSendableRequestItem.nullItem };
	private int cachedCooldownTries;
	private long cachedCooldownTime;
	
	private static volatile boolean logMINOR;
	
	static {
		Logger.registerClass(BaseSingleFileFetcher.class);
	}

	protected BaseSingleFileFetcher(ClientKey key, int maxRetries, FetchContext ctx, ClientRequester parent, boolean deleteFetchContext, boolean realTimeFlag) {
		super(parent, realTimeFlag);
		this.deleteFetchContext = deleteFetchContext;
		if(logMINOR)
			Logger.minor(this, "Creating BaseSingleFileFetcher for "+key);
		retryCount = 0;
		this.maxRetries = maxRetries;
		this.key = key;
		this.ctx = ctx;
		if(ctx == null) throw new NullPointerException();
	}
	
	protected BaseSingleFileFetcher() {
	    // For serialization.
	    key = null;
	    maxRetries = 0;
	    ctx = null;
	}

	@Override
	public long countAllKeys(ClientContext context) {
		return 1;
	}
	
	@Override
	public long countSendableKeys(ClientContext context) {
		return 1;
	}
	
	@Override
	public SendableRequestItem chooseKey(KeysFetchingLocally fetching, ClientContext context) {
		Key k = key.getNodeKey(false);
		if(fetching.hasKey(k, this)) return null;
		long l = fetching.checkRecentlyFailed(k, realTimeFlag);
		long now = System.currentTimeMillis();
		if(l > 0 && l > now) {
			if(maxRetries == -1 || (maxRetries >= RequestScheduler.COOLDOWN_RETRIES)) {
				// FIXME synchronization!!!
				if(logMINOR) Logger.minor(this, "RecentlyFailed -> cooldown until "+TimeUtil.formatTime(l-now)+" on "+this);
				MyCooldownTrackerItem tracker = makeCooldownTrackerItem(context);
				tracker.cooldownWakeupTime = Math.max(tracker.cooldownWakeupTime, l);
				return null;
			} else {
				this.onFailure(new LowLevelGetException(LowLevelGetException.RECENTLY_FAILED), null, context);
				return null;
			}
		}
		return keys[0];
	}
	
	@Override
	public ClientKey getKey(SendableRequestItem token) {
		return key;
	}
	
	@Override
	public FetchContext getContext() {
		return ctx;
	}

	@Override
	public boolean isSSK() {
		return key instanceof ClientSSK;
	}

	/** Try again - returns true if we can retry */
	protected boolean retry(ClientContext context) {
		if(isEmpty()) {
			if(logMINOR) Logger.minor(this, "Not retrying because empty");
			return false; // Cannot retry e.g. because we got the block and it failed to decode - that's a fatal error.
		}
		// We want 0, 1, ... maxRetries i.e. maxRetries+1 attempts (maxRetries=0 => try once, no retries, maxRetries=1 = original try + 1 retry)
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(context);
		int r;
		if(maxRetries == -1)
			r = ++tracker.retryCount;
		else
			r = ++retryCount;
		if(logMINOR)
			Logger.minor(this, "Attempting to retry... (max "+maxRetries+", current "+r+") on "+this+" finished="+finished+" cancelled="+cancelled);
		if((r <= maxRetries) || (maxRetries == -1)) {
			checkCachedCooldownData();
			if(cachedCooldownTries == 0 || r % cachedCooldownTries == 0) {
				// Add to cooldown queue. Don't reschedule yet.
				long now = System.currentTimeMillis();
				if(tracker.cooldownWakeupTime > now) {
					Logger.error(this, "Already on the cooldown queue for "+this+" until "+freenet.support.TimeUtil.formatTime(tracker.cooldownWakeupTime - now), new Exception("error"));
				} else {
					if(logMINOR) Logger.minor(this, "Adding to cooldown queue "+this);
					tracker.cooldownWakeupTime = now + cachedCooldownTime;
					context.cooldownTracker.setCachedWakeup(tracker.cooldownWakeupTime, this, getParentGrabArray(), context, true);
					if(logMINOR) Logger.minor(this, "Added single file fetcher into cooldown until "+TimeUtil.formatTime(tracker.cooldownWakeupTime - now));
				}
				onEnterFiniteCooldown(context);
			} else {
				// Wake the CRS after clearing cache.
				this.clearCooldown(context, true);
			}
			return true; // We will retry in any case, maybe not just not yet.
		}
		unregister(context, getPriorityClass());
		return false;
	}

	private void checkCachedCooldownData() {
		// 0/0 is illegal, and it's also the default, so use it to indicate we haven't fetched them.
		if(!(cachedCooldownTime == 0 && cachedCooldownTries == 0)) {
			// Okay, we have already got them.
			return;
		}
		innerCheckCachedCooldownData();
	}
	
	private void innerCheckCachedCooldownData() {
		cachedCooldownTries = ctx.getCooldownRetries();
		cachedCooldownTime = ctx.getCooldownTime();
	}

	protected void onEnterFiniteCooldown(ClientContext context) {
		// Do nothing.
	}

	private MyCooldownTrackerItem makeCooldownTrackerItem(ClientContext context) {
		return (MyCooldownTrackerItem) context.cooldownTracker.make(this);
	}

	@Override
	public CooldownTrackerItem makeCooldownTrackerItem() {
		return new MyCooldownTrackerItem();
	}
	
	@Override
	public ClientRequester getClientRequest() {
		return parent;
	}

	@Override
	public short getPriorityClass() {
		return parent.getPriorityClass();
	}

	public void cancel(ClientContext context) {
		synchronized(this) {
			cancelled = true;
		}
		unregisterAll(context);
	}
	
	/**
	 * Remove the pendingKeys item and then remove from the queue as well.
	 * Call unregister(container) if you only want to remove from the queue.
	 */
	public void unregisterAll(ClientContext context) {
		getScheduler(context).removePendingKeys(this, false);
		unregister(context, (short)-1);
	}

	@Override
	public void unregister(ClientContext context, short oldPrio) {
		context.cooldownTracker.remove(this);
		super.unregister(context, oldPrio);
	}

	@Override
	public synchronized boolean isCancelled() {
		return cancelled;
	}
	
	public synchronized boolean isEmpty() {
		return cancelled || finished;
	}
	
	@Override
	public RequestClient getClient() {
		return parent.getClient();
	}

	public void onGotKey(Key key, KeyBlock block, ClientContext context) {
		synchronized(this) {
			if(finished) {
				if(logMINOR)
					Logger.minor(this, "onGotKey() called twice on "+this, new Exception("debug"));
				return;
			}
			finished = true;
			if(isCancelled()) return;
			if(key == null)
				throw new NullPointerException();
			if(this.key == null)
				throw new NullPointerException("Key is null on "+this);
			if(!key.equals(this.key.getNodeKey(false))) {
				Logger.normal(this, "Got sent key "+key+" but want "+this.key+" for "+this);
				return;
			}
		}
		unregister(context, getPriorityClass()); // Key has already been removed from pendingKeys
		onSuccess(block, false, null, context);
	}
	
	public void onSuccess(KeyBlock lowLevelBlock, boolean fromStore, SendableRequestItem token, ClientContext context) {
		ClientKeyBlock block;
		try {
			block = Key.createKeyBlock(this.key, lowLevelBlock);
			onSuccess(block, fromStore, token, context);
		} catch (KeyVerifyException e) {
			onBlockDecodeError(token, context);
		}
	}
	
	protected abstract void onBlockDecodeError(SendableRequestItem token, ClientContext context);

	/** Called when/if the low-level request succeeds. */
	public abstract void onSuccess(ClientKeyBlock block, boolean fromStore, Object token, ClientContext context);
	
	@Override
	public long getCooldownWakeup(SendableRequestItem token, ClientContext context) {
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(context);
		return tracker.cooldownWakeupTime;
	}

	public void schedule(ClientContext context) {
		if(key == null) throw new NullPointerException();
		try {
			getScheduler(context).register(this, new SendableGet[] { this }, persistent, ctx.blocks, false);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public void reschedule(ClientContext context) {
		try {
			getScheduler(context).register(null, new SendableGet[] { this }, persistent, ctx.blocks, true);
		} catch (KeyListenerConstructionException e) {
			Logger.error(this, "Impossible: "+e+" on "+this, e);
		}
	}
	
	public SendableGet getRequest(Key key) {
		return this;
	}

	@Override
	public Key[] listKeys() {
		synchronized(this) {
			if(cancelled || finished)
				return new Key[0];
		}
		return new Key[] { key.getNodeKey(true) };
	}

	@Override
	public KeyListener makeKeyListener(ClientContext context, boolean onStartup) {
		synchronized(this) {
			if(finished) return null;
			if(cancelled) return null;
		}
		if(key == null) {
			Logger.error(this, "Key is null - left over BSSF? on "+this+" in makeKeyListener()", new Exception("error"));
			return null;
		}
		Key newKey = key.getNodeKey(true);
		if(parent == null) {
			Logger.error(this, "Parent is null on "+this+" persistent="+persistent+" key="+key+" ctx="+ctx);
			return null;
		}
		short prio = parent.getPriorityClass();
		KeyListener ret = new SingleKeyListener(newKey, this, prio, persistent, realTimeFlag);
		return ret;
	}

	protected abstract void notFoundInStore(ClientContext context);
	
	@Override
	public boolean preRegister(ClientContext context, boolean toNetwork) {
		if(!toNetwork) return false;
		boolean localOnly = ctx.localRequestOnly;
		if(localOnly) {
			notFoundInStore(context);
			return true;
		}
		parent.toNetwork(context);
		return false;
	}
	
	@Override
	public synchronized long getCooldownTime(ClientContext context, long now) {
		if(cancelled || finished) return -1;
		MyCooldownTrackerItem tracker = makeCooldownTrackerItem(context);
		long wakeTime = tracker.cooldownWakeupTime;
		if(wakeTime <= now)
			tracker.cooldownWakeupTime = wakeTime = 0;
		KeysFetchingLocally fetching = getScheduler(context).fetchingKeys();
		if(wakeTime <= 0 && fetching.hasKey(getNodeKey(null), this)) {
			wakeTime = Long.MAX_VALUE;
			// tracker.cooldownWakeupTime is only set for a real cooldown period, NOT when we go into hierarchical cooldown because the request is already running.
		}
		if(wakeTime == 0)
			return 0;
		HasCooldownCacheItem parentRGA = getParentGrabArray();
		context.cooldownTracker.setCachedWakeup(wakeTime, this, parentRGA, context, true);
		return wakeTime;
	}
	
	/** Reread the cached cooldown values (and anything else) from the FetchContext
	 * after it changes. FIXME: Ideally this should be a generic mechanism, but
	 * that looks too complex without significant changes to data structures.
	 * For now it's just a hack to make changing the polling interval in USKs work.
	 * See bug https://bugs.freenetproject.org/view.php?id=4984
	 * @param container The database if this is a persistent request.
	 * @param context The context object.
	 */
	public void onChangedFetchContext(ClientContext context) {
		synchronized(this) {
			if(cancelled || finished) return;
		}
		innerCheckCachedCooldownData();
	}
	
    public void onResume(ClientContext context) {
        schedule(context);
    }

}
