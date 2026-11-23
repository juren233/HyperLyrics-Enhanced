/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.relay;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.StrictMode;
import android.util.Log;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Process-lifetime Binder identity handed to other processes in place of a module-generation Binder.
 *
 * <p>This class is compiled into its own dex and defined by a ClassLoader whose parent is the boot
 * class loader, so a remote reference to it never keeps a module generation's ClassLoader alive.
 * It may only use framework and JDK types, and its public methods are an interface between module
 * versions: a newer generation may drive a relay an older version created, so they never change.
 *
 * <p>Each generation attaches its own Binder through {@link #attach(IBinder)} and detaches with
 * {@code attach(null)}; a call arriving while detached is answered as an unknown transaction:
 * dropped, and a two-way caller reads no result. During a hot reload the retiring generation calls
 * {@link #hold()} instead: until the next generation attaches, the latest call of each code given
 * at construction is kept, then replayed into the new target in arrival order before it receives
 * anything else. Only codes whose call is a setter whose latest value replaces earlier ones belong
 * there; a call the caller needs an answer to cannot be answered later and is still dropped.
 */
public final class GenerationRelayBinder extends Binder implements Consumer<IBinder> {
    private static final String TAG = "GenerationRelay";

    private final int[] heldCodes;
    private final Object lock = new Object();
    /** Latest call per held code, in arrival order of that latest call. Guarded by {@link #lock}. */
    private final LinkedHashMap<Integer, Parcel> held = new LinkedHashMap<>();
    private volatile IBinder target;
    private boolean holding;
    /** Bumped by every state change, so a replay can tell its own target closed the relay. */
    private int epoch;

    public GenerationRelayBinder() {
        this(new int[0]);
    }

    public GenerationRelayBinder(int[] heldCodes) {
        this.heldCodes = heldCodes.clone();
        Arrays.sort(this.heldCodes);
    }

    @Override
    public void accept(IBinder newTarget) {
        attach(newTarget);
    }

    /**
     * Points the relay at {@code newTarget} after replaying the calls held for it, or with
     * {@code null} detaches it and discards anything held. Returns how many calls were replayed.
     */
    public int attach(IBinder newTarget) {
        synchronized (lock) {
            int mine = ++epoch;
            if (newTarget == null) {
                target = null;
                holding = false;
                discardHeld();
                return 0;
            }
            int replayed = 0;
            Iterator<Map.Entry<Integer, Parcel>> calls = held.entrySet().iterator();
            while (calls.hasNext()) {
                Map.Entry<Integer, Parcel> call = calls.next();
                calls.remove();
                if (replay(newTarget, call.getKey(), call.getValue())) replayed++;
                // The replayed call itself detached or re-attached this relay (a provider's
                // disconnect closes its connection): that later state wins.
                if (epoch != mine) return replayed;
                calls = held.entrySet().iterator();
            }
            holding = false;
            target = newTarget;
            return replayed;
        }
    }

    /** Detaches the relay and keeps the latest call of each held code until the next attach. */
    public void hold() {
        synchronized (lock) {
            ++epoch;
            target = null;
            holding = true;
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        IBinder current = target;
        if (current == null) {
            synchronized (lock) {
                current = target;
                if (current == null) {
                    if (!holding || Arrays.binarySearch(heldCodes, code) < 0) return false;
                    Parcel copy = Parcel.obtain();
                    copy.appendFrom(data, 0, data.dataSize());
                    Parcel replaced = held.remove(code);
                    if (replaced != null) replaced.recycle();
                    held.put(code, copy);
                    return true;
                }
            }
        }
        return current.transact(code, data, reply, flags);
    }

    /**
     * The held parcel carries the caller's interface header; reading it sets the caller's StrictMode
     * policy and work source on this thread, which a remote transaction would restore afterwards.
     */
    private static boolean replay(IBinder newTarget, int code, Parcel data) {
        Parcel reply = Parcel.obtain();
        StrictMode.ThreadPolicy policy = StrictMode.getThreadPolicy();
        long workSource = Binder.setCallingWorkSourceUid(Binder.getCallingWorkSourceUid());
        try {
            data.setDataPosition(0);
            return newTarget.transact(code, data, reply, 0);
        } catch (Throwable error) {
            Log.e(TAG, "held call " + code + " could not be replayed", error);
            return false;
        } finally {
            Binder.restoreCallingWorkSource(workSource);
            StrictMode.setThreadPolicy(policy);
            reply.recycle();
            data.recycle();
        }
    }

    private void discardHeld() {
        for (Parcel parcel : held.values()) parcel.recycle();
        held.clear();
    }
}
