/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Build;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;

/**
 * A utility class to inform Netd of UID permisisons.
 * Does a mass update at boot and then monitors for app install/remove.
 *
 * @hide
 */
public class PermissionMonitor {
    private static final String TAG = "PermissionMonitor";
    private static final boolean DBG = true;
    protected static final Boolean SYSTEM = Boolean.TRUE;
    protected static final Boolean NETWORK = Boolean.FALSE;
    private static final int VERSION_Q = Build.VERSION_CODES.Q;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final INetworkManagementService mNetd;

    // Values are User IDs.
    private final Set<Integer> mUsers = new HashSet<>();

    // Keys are App IDs. Values are true for SYSTEM permission and false for NETWORK permission.
    private final Map<Integer, Boolean> mApps = new HashMap<>();

    public PermissionMonitor(Context context, INetworkManagementService netd) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mUserManager = UserManager.get(context);
        mNetd = netd;
    }

    // Intended to be called only once at startup, after the system is ready. Installs a broadcast
    // receiver to monitor ongoing UID changes, so this shouldn't/needn't be called again.
    public synchronized void startMonitoring() {
        log("Monitoring");

        List<PackageInfo> apps = mPackageManager.getInstalledPackages(GET_PERMISSIONS);
        if (apps == null) {
            loge("No apps");
            return;
        }

        for (PackageInfo app : apps) {
            int uid = app.applicationInfo != null ? app.applicationInfo.uid : INVALID_UID;
            if (uid < 0) {
                continue;
            }

            boolean isNetwork = hasNetworkPermission(app);
            boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);

            if (isNetwork || hasRestrictedPermission) {
                Boolean permission = mApps.get(uid);
                // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
                // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
                if (permission == null || permission == NETWORK) {
                    mApps.put(uid, hasRestrictedPermission);
                }
            }
        }

        List<UserInfo> users = mUserManager.getUsers(true);  // exclude dying users
        if (users != null) {
            for (UserInfo user : users) {
                mUsers.add(user.id);
            }
        }

        log("Users: " + mUsers.size() + ", Apps: " + mApps.size());
        update(mUsers, mApps, true);
    }

    @VisibleForTesting
    static boolean isVendorApp(@NonNull ApplicationInfo appInfo) {
        return appInfo.isVendor() || appInfo.isOem() || appInfo.isProduct();
    }

    @VisibleForTesting
    protected int getDeviceFirstSdkInt() {
        return Build.VERSION.FIRST_SDK_INT;
    }

    @VisibleForTesting
    boolean hasPermission(PackageInfo app, String permission) {
        if (app.requestedPermissions != null) {
            for (String p : app.requestedPermissions) {
                if (permission.equals(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNetworkPermission(PackageInfo app) {
        return hasPermission(app, CHANGE_NETWORK_STATE);
    }

    private boolean hasRestrictedNetworkPermission(PackageInfo app) {
        // TODO : remove this check in the future(b/31479477). All apps should just
        // request the appropriate permission for their use case since android Q.
        if (app.applicationInfo != null) {
            // Backward compatibility for b/114245686, on devices that launched before Q daemons
            // and apps running as the system UID are exempted from this check.
            if (app.applicationInfo.uid == SYSTEM_UID && getDeviceFirstSdkInt() < VERSION_Q) {
                return true;
            }

            if (app.applicationInfo.targetSdkVersion < VERSION_Q
                    && isVendorApp(app.applicationInfo)) {
                return true;
            }
        }
        return hasPermission(app, CONNECTIVITY_INTERNAL)
                || hasPermission(app, CONNECTIVITY_USE_RESTRICTED_NETWORKS);
    }

    private boolean hasUseBackgroundNetworksPermission(PackageInfo app) {
        // This function defines what it means to hold the permission to use
        // background networks.
        return hasPermission(app, CHANGE_NETWORK_STATE)
                || hasPermission(app, NETWORK_STACK)
                || hasRestrictedNetworkPermission(app);
    }

    public boolean hasUseBackgroundNetworksPermission(int uid) {
        final String[] names = mPackageManager.getPackagesForUid(uid);
        if (null == names || names.length == 0) return false;
        try {
            // Only using the first package name. There may be multiple names if multiple
            // apps share the same UID, but in that case they also share permissions so
            // querying with any of the names will return the same results.
            int userId = UserHandle.getUserId(uid);
            final PackageInfo app = mPackageManager.getPackageInfoAsUser(
                    names[0], GET_PERMISSIONS, userId);
            return hasUseBackgroundNetworksPermission(app);
        } catch (NameNotFoundException e) {
            // App not found.
            loge("NameNotFoundException " + names[0], e);
            return false;
        }
    }

    private int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private void update(Set<Integer> users, Map<Integer, Boolean> apps, boolean add) {
        List<Integer> network = new ArrayList<>();
        List<Integer> system = new ArrayList<>();
        for (Entry<Integer, Boolean> app : apps.entrySet()) {
            List<Integer> list = app.getValue() ? system : network;
            for (int user : users) {
                list.add(UserHandle.getUid(user, app.getKey()));
            }
        }
        try {
            if (add) {
                mNetd.setPermission("NETWORK", toIntArray(network));
                mNetd.setPermission("SYSTEM", toIntArray(system));
            } else {
                mNetd.clearPermission(toIntArray(network));
                mNetd.clearPermission(toIntArray(system));
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: " + e);
        }
    }

    /**
     * Called when a user is added. See {link #ACTION_USER_ADDED}.
     *
     * @param user The integer userHandle of the added user. See {@link #EXTRA_USER_HANDLE}.
     *
     * @hide
     */
    public synchronized void onUserAdded(int user) {
        if (user < 0) {
            loge("Invalid user in onUserAdded: " + user);
            return;
        }
        mUsers.add(user);

        Set<Integer> users = new HashSet<>();
        users.add(user);
        update(users, mApps, true);
    }

    /**
     * Called when an user is removed. See {link #ACTION_USER_REMOVED}.
     *
     * @param user The integer userHandle of the removed user. See {@link #EXTRA_USER_HANDLE}.
     *
     * @hide
     */
    public synchronized void onUserRemoved(int user) {
        if (user < 0) {
            loge("Invalid user in onUserRemoved: " + user);
            return;
        }
        mUsers.remove(user);

        Set<Integer> users = new HashSet<>();
        users.add(user);
        update(users, mApps, false);
    }

    @VisibleForTesting
    protected Boolean highestPermissionForUid(Boolean currentPermission, String name) {
        if (currentPermission == SYSTEM) {
            return currentPermission;
        }
        try {
            final PackageInfo app = mPackageManager.getPackageInfo(name, GET_PERMISSIONS);
            final boolean isNetwork = hasNetworkPermission(app);
            final boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);
            if (isNetwork || hasRestrictedPermission) {
                currentPermission = hasRestrictedPermission;
            }
        } catch (NameNotFoundException e) {
            // App not found.
            loge("NameNotFoundException " + name);
        }
        return currentPermission;
    }

    /**
     * Called when a package is added. See {link #ACTION_PACKAGE_ADDED}.
     *
     * @param packageName The name of the new package.
     * @param uid The uid of the new package.
     *
     * @hide
     */
    public synchronized void onPackageAdded(String packageName, int uid) {
        // If multiple packages share a UID (cf: android:sharedUserId) and ask for different
        // permissions, don't downgrade (i.e., if it's already SYSTEM, leave it as is).
        final Boolean permission = highestPermissionForUid(mApps.get(uid), packageName);
        if (permission != mApps.get(uid)) {
            mApps.put(uid, permission);

            Map<Integer, Boolean> apps = new HashMap<>();
            apps.put(uid, permission);
            update(mUsers, apps, true);
        }
    }

    /**
     * Called when a package is removed. See {link #ACTION_PACKAGE_REMOVED}.
     *
     * @param uid containing the integer uid previously assigned to the package.
     *
     * @hide
     */
    public synchronized void onPackageRemoved(int uid) {
        Map<Integer, Boolean> apps = new HashMap<>();

        Boolean permission = null;
        String[] packages = mPackageManager.getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            for (String name : packages) {
                permission = highestPermissionForUid(permission, name);
                if (permission == SYSTEM) {
                    // An app with this UID still has the SYSTEM permission.
                    // Therefore, this UID must already have the SYSTEM permission.
                    // Nothing to do.
                    return;
                }
            }
        }
        if (permission == mApps.get(uid)) {
            // The permissions of this UID have not changed. Nothing to do.
            return;
        } else if (permission != null) {
            mApps.put(uid, permission);
            apps.put(uid, permission);
            update(mUsers, apps, true);
        } else {
            mApps.remove(uid);
            apps.put(uid, NETWORK);  // doesn't matter which permission we pick here
            update(mUsers, apps, false);
        }
    }

    private static void log(String s) {
        if (DBG) {
            Log.d(TAG, s);
        }
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static void loge(String s, Throwable e) {
        Log.e(TAG, s, e);
    }
}