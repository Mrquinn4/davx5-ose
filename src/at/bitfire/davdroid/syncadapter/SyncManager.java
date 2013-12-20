/*******************************************************************************
 * Copyright (c) 2013 Richard Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package at.bitfire.davdroid.syncadapter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import net.fortuna.ical4j.model.ValidationException;

import org.apache.http.HttpException;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.SyncResult;
import android.util.Log;
import at.bitfire.davdroid.resource.LocalCollection;
import at.bitfire.davdroid.resource.LocalStorageException;
import at.bitfire.davdroid.resource.RecordNotFoundException;
import at.bitfire.davdroid.resource.RemoteCollection;
import at.bitfire.davdroid.resource.Resource;
import at.bitfire.davdroid.webdav.NotFoundException;
import at.bitfire.davdroid.webdav.PreconditionFailedException;

public class SyncManager {
	private static final String TAG = "davdroid.SyncManager";
	
	protected Account account;
	protected AccountManager accountManager;
	
	
	public SyncManager(Account account, AccountManager accountManager) {
		this.account = account;
		this.accountManager = accountManager;
	}

	public void synchronize(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote, boolean manualSync, SyncResult syncResult) throws LocalStorageException, IOException, HttpException {
		// PHASE 1: push local changes to server
		int	deletedRemotely = pushDeleted(local, remote),
			addedRemotely = pushNew(local, remote),
			updatedRemotely = pushDirty(local, remote);
		
		syncResult.stats.numEntries = deletedRemotely + addedRemotely + updatedRemotely;
		
		// PHASE 2A: check if there's a reason to do a sync with remote (= forced sync or remote CTag changed)
		boolean fetchCollection = syncResult.stats.numEntries > 0;
		if (manualSync) {
			Log.i(TAG, "Synchronization forced");
			fetchCollection = true;
		}
		if (!fetchCollection) {
			String	currentCTag = remote.getCTag(),
					lastCTag = local.getCTag();
			if (currentCTag == null || !currentCTag.equals(lastCTag))
				fetchCollection = true;
		}
		
		// PHASE 2B: detect details of remote changes
		Log.i(TAG, "Fetching remote resource list");
		Set<Resource>	remotelyAdded = new HashSet<Resource>(),
						remotelyUpdated = new HashSet<Resource>();
		
		Resource[] remoteResources = remote.getMemberETags();
		if (remoteResources != null) {
			for (Resource remoteResource : remoteResources) {
				try {
					Resource localResource = local.findByRemoteName(remoteResource.getName(), false);
					if (localResource.getETag() == null || !localResource.getETag().equals(remoteResource.getETag()))
						remotelyUpdated.add(remoteResource);
				} catch(RecordNotFoundException e) {
					remotelyAdded.add(remoteResource);
				}
			}
		}
		
		// PHASE 3: pull remote changes from server
		syncResult.stats.numInserts = pullNew(local, remote, remotelyAdded);
		syncResult.stats.numUpdates = pullChanged(local, remote, remotelyUpdated);
		
		Log.i(TAG, "Removing non-dirty resources that are not present remotely anymore");
		local.deleteAllExceptRemoteNames(remoteResources);
		local.commit();

		// update collection CTag
		Log.i(TAG, "Sync complete, fetching new CTag");
		local.setCTag(remote.getCTag());
		local.commit();
	}
	
	
	private int pushDeleted(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote) throws LocalStorageException, IOException, HttpException {
		int count = 0;
		Resource[] deletedResources = local.findDeleted();
		if (deletedResources != null) {
			Log.i(TAG, "Remotely removing " + deletedResources.length + " deleted resource(s) (if not changed)");
			for (Resource res : deletedResources) {
				try {
					if (res.getName() != null)	// is this resource even present remotely?
						remote.delete(res);
				} catch(NotFoundException e) {
					Log.i(TAG, "Locally-deleted resource has already been removed from server");
				} catch(PreconditionFailedException e) {
					Log.i(TAG, "Locally-deleted resource has been changed on the server in the meanwhile");
				}
				local.delete(res);
				count++;
			}
			local.commit();
		}
		return count;
	}
	
	private int pushNew(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote) throws LocalStorageException, IOException, HttpException {
		int count = 0;
		Resource[] newResources = local.findNew();
		if (newResources != null) {
			Log.i(TAG, "Uploading " + newResources.length + " new resource(s) (if not existing)");
			for (Resource res : newResources) {
				try {
					remote.add(res);
				} catch(PreconditionFailedException e) {
					Log.i(TAG, "Didn't overwrite existing resource with other content");
				} catch (ValidationException e) {
					Log.e(TAG, "Couldn't create entity for adding: " + e.toString());
				}
				local.clearDirty(res);
				count++;
			}
			local.commit();
		}
		return count;
	}
	
	private int pushDirty(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote) throws LocalStorageException, IOException, HttpException {
		int count = 0;
		Resource[] dirtyResources = local.findDirty();
		if (dirtyResources != null) {
			Log.i(TAG, "Uploading " + dirtyResources.length + " modified resource(s) (if not changed)");
			for (Resource res : dirtyResources) {
				try {
					remote.update(res);
				} catch(PreconditionFailedException e) {
					Log.i(TAG, "Locally changed resource has been changed on the server in the meanwhile");
				} catch (ValidationException e) {
					Log.e(TAG, "Couldn't create entity for updating: " + e.toString());
				}
				local.clearDirty(res);
				count++;
			}
			local.commit();
		}
		return count;
	}
	
	private int pullNew(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote, Set<Resource> resourcesToAdd) throws LocalStorageException, IOException, HttpException {
		int count = 0;
		Log.i(TAG, "Fetching " + resourcesToAdd.size() + " new remote resource(s)");
		if (!resourcesToAdd.isEmpty()) {
			for (Resource res : remote.multiGet(resourcesToAdd.toArray(new Resource[0]))) {
				Log.i(TAG, "Adding " + res.getName());
				local.add(res);
				local.commit();
				count++;
			}
		}
		return count;
	}
	
	private int pullChanged(LocalCollection<? extends Resource> local, RemoteCollection<? extends Resource> remote, Set<Resource> resourcesToUpdate) throws LocalStorageException, IOException, HttpException {
		int count = 0;
		Log.i(TAG, "Fetching " + resourcesToUpdate.size() + " updated remote resource(s)");
		if (!resourcesToUpdate.isEmpty())
			for (Resource res : remote.multiGet(resourcesToUpdate.toArray(new Resource[0]))) {
				Log.i(TAG, "Updating " + res.getName());
				local.updateByRemoteName(res);
				local.commit();
				count++;
			}
		return count;
	}

}
