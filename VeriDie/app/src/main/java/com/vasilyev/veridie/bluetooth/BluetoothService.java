package com.vasilyev.veridie.bluetooth;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;

import com.vasilyev.veridie.interop.Bridge;
import com.vasilyev.veridie.interop.Command;
import com.vasilyev.veridie.interop.CommandHandler;
import com.vasilyev.veridie.interop.Event;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

public class BluetoothService extends Service
{
    public interface Callbacks
    {
        void deviceDiscovered(BluetoothDevice device);
        void deviceConnected(BluetoothDevice device);
        void connectFailed(BluetoothDevice device);
    }

    public class BluetoothBinder extends Binder
    {
        public BluetoothService getService()
        {
            return BluetoothService.this;
        }
    }

    private final BroadcastReceiver m_btStateReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            int state = -1;
            state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, state);
            Event e;
            long resultCode;
            switch (state) {
            case BluetoothAdapter.STATE_ON:
            case BluetoothAdapter.STATE_TURNING_ON:
                e = Event.BLUETOOTH_ON;
                resultCode = Command.ERROR_NO_ERROR;
                break;
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                e = Event.BLUETOOTH_OFF;
                resultCode = Command.ERROR_USER_DECLINED;
                break;
            default:
                return;
            }
            for (Iterator<Command> it = m_pendingCmds.iterator(); it.hasNext(); ) {
                Command cmd = it.next();
                if (cmd.getId() == Command.ID_ENABLE_BLUETOOTH) {
                    cmd.respond(resultCode);
                    it.remove();
                }
            }
            Bridge.send(e);
        }
    };
    private final BroadcastReceiver m_discoveryReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getName() != null && m_callbacks != null)
                    m_callbacks.deviceDiscovered(device);
            }
        }
    };
    private final BroadcastReceiver m_discoverabilityReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            int currentScanMode = -1;
            currentScanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, currentScanMode);
            if (currentScanMode == -1)
                return;
            long result = Command.ERROR_USER_DECLINED;
            if (currentScanMode != BluetoothAdapter.SCAN_MODE_NONE) {
                result = listenAndAccept() ? Command.ERROR_NO_ERROR : Command.ERROR_LISTEN_FAILED;
            }
            for (Iterator<Command> it = m_pendingCmds.iterator(); it.hasNext(); ) {
                Command cmd = it.next();
                if (cmd.getId() == Command.ID_START_LISTENING) {
                    cmd.respond(result);
                    it.remove();
                }
            }
        }
    };
    private final Map<Integer, Command.Callback> m_handlers = new HashMap<Integer, Command.Callback>()
    {{
        put(Command.ID_START_DISCOVERY, BluetoothService.this::startDiscovering);
        put(Command.ID_STOP_DISCOVERY, BluetoothService.this::stopDiscovering);
        put(Command.ID_START_LISTENING, BluetoothService.this::startListening);
        put(Command.ID_STOP_LISTENING, BluetoothService.this::stopListening);
        put(Command.ID_ENABLE_BLUETOOTH, BluetoothService.this::enableBluetooth);
        put(Command.ID_CLOSE_CONNECTION, BluetoothService.this::forwardToConnectionManager);
        put(Command.ID_SEND_MESSAGE, BluetoothService.this::forwardToConnectionManager);
        put(Command.ID_RESET_CONNECTIONS, BluetoothService.this::resetBluetooth);
    }};

    private static final String TAG = BluetoothService.class.getName();

    private final HandlerThread m_thread;
    private final Handler m_internalHandler;

    private Callbacks m_callbacks;
    private ConnectionManager m_connectionMgr;
    private CommandHandler m_connectionCmdHandler;

    private final BluetoothAdapter m_btAdapter = BluetoothAdapter.getDefaultAdapter();
    private final List<Command> m_pendingCmds = new ArrayList<>();
    private Thread m_acceptorThread;

    private UUID m_appUuid;
    private String m_appName;

    // ---------------Lifecycle-methods-------------------------------------------------------------

    public BluetoothService()
    {
        m_thread = new HandlerThread(TAG);
        m_thread.start();
        m_internalHandler = new Handler(m_thread.getLooper());
    }

    @Override
    public void onCreate()
    {
        super.onCreate();
        m_internalHandler.postAtFrontOfQueue(() -> {
            CommandHandler myCmdHandler = new CommandHandler(m_thread.getLooper())
            {
                @Override
                protected void onCommandReceived(Command cmd)
                {
                    try {
                        m_handlers.get(cmd.getId()).onCommand(cmd);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        cmd.respond(Command.ERROR_INVALID_STATE);
                    }
                }
            };
            m_connectionMgr = new ConnectionManager();
            m_connectionMgr.start();
            m_connectionCmdHandler = m_connectionMgr.getCommandHandler();
            registerReceiver(m_btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    null, m_internalHandler);
            Bridge.setBtCmdHandler(myCmdHandler);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy()
    {
        m_internalHandler.postAtFrontOfQueue(() -> {
            Bridge.setBtCmdHandler(null);
            if (m_acceptorThread != null)
                m_acceptorThread.interrupt();
            if (m_connectionMgr != null)
                m_connectionMgr.quitSafely();
            if (m_thread != null)
                m_thread.quitSafely();
        });
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        m_internalHandler.postAtFrontOfQueue(() -> {
            m_callbacks = null;
        });
        return new BluetoothBinder();
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        m_internalHandler.postAtFrontOfQueue(() -> {
            m_callbacks = null;
        });
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent)
    {
        try {
            unregisterReceiver(m_btStateReceiver);
            unregisterReceiver(m_discoverabilityReceiver);
            unregisterReceiver(m_discoveryReceiver);
        }
        catch (Exception e) {
        }
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    // ---------------Public-interface--------------------------------------------------------------

    public void setCallbacks(@NonNull Callbacks cb, @NonNull Executor ex)
    {
        m_internalHandler.post(() -> {
            m_callbacks = new Callbacks()
            {
                final Executor m_ex = ex;
                final BluetoothService.Callbacks m_cb = cb;

                @Override
                public void deviceDiscovered(BluetoothDevice device)
                {
                    m_ex.execute(() -> {
                        m_cb.deviceDiscovered(device);
                    });
                }

                @Override
                public void deviceConnected(BluetoothDevice device)
                {
                    m_ex.execute(() -> {
                        m_cb.deviceConnected(device);
                    });
                }

                @Override
                public void connectFailed(BluetoothDevice device)
                {
                    m_ex.execute(() -> {
                        m_cb.connectFailed(device);
                    });
                }
            };
        });
    }

    // Called when user wants to connect to a discovered device
    public void connectDevice(BluetoothDevice device)
    {
        m_internalHandler.post(() -> {
            if (m_appUuid == null)
                return;

            BluetoothSocket socket = null;
            try {
                Log.d(TAG, "BluetoothService: connecting to a device...");
                socket = device.createRfcommSocketToServiceRecord(m_appUuid);
                socket.connect();
            }
            catch (Exception e) {
                e.printStackTrace();
                if (socket != null)
                    close(socket);
                if (m_callbacks != null)
                    m_callbacks.connectFailed(device);
                return;
            }

            try {
                m_connectionMgr.addConnection(socket);
            }
            catch (Exception e) {
                e.printStackTrace();
                close(socket);
                return;
            }
            if (m_callbacks != null)
                m_callbacks.deviceConnected(socket.getRemoteDevice());
        });
    }

    // Called when user wants to disconnect an accepted device
    public void disconnectDevice(BluetoothDevice device)
    {
        m_connectionMgr.removeConnection(device.getName());
    }

    // ---------------Command-callbacks-------------------------------------------------------------

    private void enableBluetooth(Command cmd)
    {
        if (m_btAdapter == null) {
            cmd.respond(Command.ERROR_NO_BT_ADAPTER);
            return;
        }
        if (m_btAdapter.isEnabled()) {
            cmd.respond(Command.ERROR_NO_ERROR);
            return;
        }
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        m_pendingCmds.add(cmd);
        startActivity(enableBtIntent);

        // if user already declined no broadcast will be sent, so we need to make sure that pending
        // command is answered
        m_internalHandler.postDelayed(() -> {
            for (Iterator<Command> it = m_pendingCmds.iterator(); it.hasNext(); ) {
                Command c = it.next();
                if (c.getId() == Command.ID_ENABLE_BLUETOOTH) {
                    c.respond(Command.ERROR_USER_DECLINED);
                    it.remove();
                }
            }
        }, 5000);
    }

    private void startListening(Command cmd)
    {
        registerReceiver(m_discoverabilityReceiver, new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED),
                null, m_internalHandler);
        if (m_appUuid == null)
            m_appUuid = UUID.fromString(cmd.getArgs()[0]);
        if (m_appName == null)
            m_appName = cmd.getArgs()[1];
        int duration = Integer.parseInt(cmd.getArgs()[2]);

        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration);
        discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        m_pendingCmds.add(cmd);
        startActivity(discoverableIntent);

        // if user already declined no broadcast will be sent, so we need to make sure that pending
        // command is answered
        m_internalHandler.postDelayed(() -> {
            for (Iterator<Command> it = m_pendingCmds.iterator(); it.hasNext(); ) {
                Command c = it.next();
                if (c.getId() == Command.ID_START_LISTENING) {
                    c.respond(Command.ERROR_USER_DECLINED);
                    it.remove();
                }
            }
        }, 5000);
    }

    private void stopListening(Command cmd)
    {
        if (m_acceptorThread != null && m_acceptorThread.isAlive() && !m_acceptorThread.isInterrupted()) {
            m_acceptorThread.interrupt();
            m_pendingCmds.add(cmd);
        } else {
            cmd.respond(Command.ERROR_NO_ERROR);
        }
        unregisterReceiver(m_discoverabilityReceiver);
    }

    private void startDiscovering(Command cmd)
    {
        registerReceiver(m_discoveryReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND),
                null, m_internalHandler);
        if (m_appUuid == null)
            m_appUuid = UUID.fromString(cmd.getArgs()[0]);
        if (m_appName == null)
            m_appName = cmd.getArgs()[1];
        boolean includePaired = Boolean.parseBoolean(cmd.getArgs()[2]);

        if (m_btAdapter == null) {
            cmd.respond(Command.ERROR_NO_BT_ADAPTER);
            return;
        }

        if (includePaired && m_callbacks != null) {
            Set<BluetoothDevice> pairedDevices = m_btAdapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices)
                m_callbacks.deviceDiscovered(device);
        }
        boolean success = m_btAdapter.startDiscovery();
        cmd.respond(success ? Command.ERROR_NO_ERROR : Command.ERROR_INVALID_STATE);
    }

    private void stopDiscovering(Command cmd)
    {
        unregisterReceiver(m_discoveryReceiver);
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void resetBluetooth(Command cmd)
    {
        if (m_acceptorThread != null)
            m_acceptorThread.interrupt();
        m_connectionMgr.quit();
        m_connectionMgr = new ConnectionManager();
        m_connectionMgr.start();
        m_connectionCmdHandler = m_connectionMgr.getCommandHandler();
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void forwardToConnectionManager(Command cmd)
    {
        if (m_connectionCmdHandler == null) {
            cmd.respond(Command.ERROR_INVALID_STATE);
            return;
        }
        Message msg = m_connectionCmdHandler.obtainMessage(cmd.getId(), cmd);
        msg.sendToTarget();
    }

    // ---------------Misc--------------------------------------------------------------------------

    private boolean listenAndAccept()
    {
        if (m_acceptorThread != null && m_acceptorThread.isAlive() && !m_acceptorThread.isInterrupted())
            return true;
        if (m_appName == null || m_appUuid == null)
            return false;
        BluetoothServerSocket serverSocket = null;
        try {
            serverSocket = m_btAdapter.listenUsingRfcommWithServiceRecord(m_appName, m_appUuid);
        }
        catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        final BluetoothServerSocket listener = serverSocket;
        m_acceptorThread = new Thread()
        {
            @Override
            public void run()
            {
                BluetoothSocket acceptedSocket = null;
                while (!isInterrupted()) {
                    try {
                        acceptedSocket = listener.accept(1000);
                    }
                    catch (IOException e) { /*timeout or error*/ }
                    if (acceptedSocket == null)
                        continue;
                    try {
                        m_connectionMgr.addConnection(acceptedSocket);
                        if (m_callbacks != null)
                            m_callbacks.deviceConnected(acceptedSocket.getRemoteDevice());
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        close(acceptedSocket);
                    }
                    acceptedSocket = null;
                }
                close(listener);
                m_internalHandler.post(() -> {
                    for (Iterator<Command> it = m_pendingCmds.iterator(); it.hasNext(); ) {
                        Command cmd = it.next();
                        if (cmd.getId() == Command.ID_STOP_LISTENING) {
                            cmd.respond(Command.ERROR_NO_ERROR);
                            it.remove();
                        }
                    }
                });
            }
        };
        m_acceptorThread.start();
        return true;
    }

    private static void close(Closeable socket)
    {
        try {
            socket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
