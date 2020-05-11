package com.vasilyev.veridie;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.vasilyev.veridie.bluetooth.BluetoothService;
import com.vasilyev.veridie.fragments.ConnectingFragment;
import com.vasilyev.veridie.fragments.IdleFragment;
import com.vasilyev.veridie.fragments.NegotiationFragment;
import com.vasilyev.veridie.fragments.PlayingFragment;
import com.vasilyev.veridie.fragments.RequestDialogFragment;
import com.vasilyev.veridie.interop.Bridge;
import com.vasilyev.veridie.interop.Command;
import com.vasilyev.veridie.interop.CommandHandler;
import com.vasilyev.veridie.interop.Event;
import com.vasilyev.veridie.utils.Cast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements BluetoothService.Callbacks,
        IdleFragment.Callbacks,
        ConnectingFragment.Callbacks,
        PlayingFragment.Callbacks,
        RequestDialogFragment.Callbacks
{
    static {
        System.loadLibrary("veridie");
    }

    private static final String TAG = "MainActivity";
    private static final String SAVED_FRAGMENT = "MainActivity.SAVED_FRAGMENT";
    private static final int REQUEST_LOCATION_ACCESS = 10;
    private static final String TAG_REQUEST_DIALOG = "TAG_REQUEST_DIALOG";

    private final ServiceConnection m_bluetoothConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            m_bluetooth = ((BluetoothService.BluetoothBinder)service).getService();

            final Handler mainHandler = new Handler(Looper.getMainLooper());
            m_bluetooth.setCallbacks(MainActivity.this, mainHandler::post);
        }

        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            m_bluetooth = null;
        }
    };
    private final Map<Integer, Command.Callback> m_handlers = new HashMap<Integer, Command.Callback>()
    {{
        put(Command.ID_NEGOTIATION_START, MainActivity.this::negotiationStart);
        put(Command.ID_NEGOTIATION_STOP, MainActivity.this::negotiationStop);
        put(Command.ID_SHOW_AND_EXIT, MainActivity.this::showAndExit);
        put(Command.ID_SHOW_TOAST, MainActivity.this::showToast);
        put(Command.ID_SHOW_NOTIFICATION, MainActivity.this::showNotification);
        put(Command.ID_SHOW_REQUEST, MainActivity.this::showRequest);
        put(Command.ID_SHOW_RESPONSE, MainActivity.this::showResponse);
        put(Command.ID_RESET_GAME, MainActivity.this::resetGame);
    }};
    private final CommandHandler m_cmdHandler = new CommandHandler(Looper.getMainLooper())
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
    private final List<Command> m_pendingCmds = new ArrayList<>();

    private BluetoothService m_bluetooth;

    // ---------------Lifecycle-methods-------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Bridge.setUiCmdHandler(m_cmdHandler);

        Fragment currentFragment;
        if (savedInstanceState != null)
            currentFragment = getSupportFragmentManager().getFragment(savedInstanceState, SAVED_FRAGMENT);
        else {
            currentFragment = IdleFragment.newInstance();
            Bridge.send(Event.GAME_STOPPED);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, currentFragment)
                .commit();

        Intent startBtIntent = new Intent(this, BluetoothService.class);
        startService(startBtIntent);
        Intent bindBtIntent = new Intent(this, BluetoothService.class);
        bindService(bindBtIntent, m_bluetoothConnection, BIND_AUTO_CREATE | BIND_IMPORTANT);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);
        Fragment currentState = getSupportFragmentManager().findFragmentById(R.id.container);
        if (currentState != null)
            getSupportFragmentManager().putFragment(outState, SAVED_FRAGMENT, currentState);
    }

    @Override
    protected void onDestroy()
    {
        unbindService(m_bluetoothConnection);
        Bridge.setUiCmdHandler(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed()
    {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.dialog_exit_title)
                .setMessage(R.string.dialog_exit_prompt)
                .setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                    Bridge.send(Event.GAME_STOPPED);
                    finishAndRemoveTask();
                    System.exit(0);
                })
                .create();
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        switch (requestCode) {
        case REQUEST_LOCATION_ACCESS:
            onStartConnectingButtonPressed();
            break;
        default:
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // ---------------Command-callbacks-------------------------------------------------------------

    private void negotiationStart(Command cmd)
    {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, NegotiationFragment.newInstance())
                .commit();
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void negotiationStop(Command cmd)
    {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, PlayingFragment.newInstance(cmd.getArgs()[0]))
                .commit();
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void showAndExit(Command cmd)
    {
        cmd.respond(Command.ERROR_NO_ERROR);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.dialog_fatal_title)
                .setMessage(cmd.getArgs()[0])
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
                    finishAndRemoveTask();
                    System.exit(0);
                })
                .create();
        dialog.show();
    }

    private void showToast(Command cmd)
    {
        String message = cmd.getArgs()[0];
        int lengthSec = Integer.parseInt(cmd.getArgs()[1]);
        Toast.makeText(getApplicationContext(), message,
                lengthSec >= 5 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void showNotification(Command cmd)
    {
        cmd.respond(Command.ERROR_NO_ERROR);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(R.string.dialog_notification_title)
                .setMessage(cmd.getArgs()[0])
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> dialogInterface.dismiss())
                .create();
        dialog.show();
    }

    private void showRequest(Command cmd)
    {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        String d = cmd.getArgs()[0];
        int size = Integer.parseInt(cmd.getArgs()[1]);
        int threshold = Integer.parseInt(cmd.getArgs()[2]);
        String from = cmd.getArgs()[3];
        Cast.Request r = new Cast.Request(d, size, threshold, this);

        ((PlayingFragment)current).addRequest(from, r);
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void showResponse(Command cmd)
    {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        String values = cmd.getArgs()[0];
        String d = cmd.getArgs()[1];
        int successCount = Integer.parseInt(cmd.getArgs()[2]);
        String from = cmd.getArgs()[3];
        Cast.Result r = new Cast.Result(d, values, successCount);

        ((PlayingFragment)current).addResult(from, r);
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    private void resetGame(Command cmd)
    {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, IdleFragment.newInstance())
                .commit();
        cmd.respond(Command.ERROR_NO_ERROR);
    }

    // ---------------BluetoothService-callbacks----------------------------------------------------

    @Override
    public void deviceDiscovered(BluetoothDevice device)
    {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        if (current instanceof ConnectingFragment)
            ((ConnectingFragment)current).addDiscoveredDevice(device);
    }

    @Override
    public void deviceConnected(BluetoothDevice device)
    {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        if (current instanceof ConnectingFragment) {
            ((ConnectingFragment)current).removeDiscoveredDevice(device);
            ((ConnectingFragment)current).addConnectedDevice(device);
        }
    }

    @Override
    public void connectFailed(BluetoothDevice device)
    {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
        if (current instanceof ConnectingFragment) {
            ((ConnectingFragment)current).removeDiscoveredDevice(device);
            ((ConnectingFragment)current).addDiscoveredDevice(device);
        }
    }

    // ---------------IdleFragment-callbacks--------------------------------------------------------

    @Override
    public void onStartConnectingButtonPressed()
    {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION_ACCESS);
            return;
        }

        Bridge.send(Event.NEW_GAME_REQUESTED);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, ConnectingFragment.newInstance())
                .commit();
    }

    // ---------------ConnectingFragment-callbacks--------------------------------------------------

    @Override
    public void onConnectDevicePressed(BluetoothDevice device)
    {
        if (m_bluetooth != null)
            m_bluetooth.connectDevice(device);
    }

    @Override
    public void onDisconnectDevicePressed(BluetoothDevice device)
    {
        if (m_bluetooth != null) {
            m_bluetooth.disconnectDevice(device);
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.container);
            ((ConnectingFragment)current).removeConnectedDevice(device);
        }
    }

    @Override
    public void onFinishedConnecting()
    {
        Bridge.send(Event.CONNECTIVITY_ESTABLISHED);
    }

    // ---------------PlayingFragment-callbacks-----------------------------------------------------

    @Override
    public void onCastRequestPressed()
    {
        RequestDialogFragment f = RequestDialogFragment.newInstance();
        f.show(getFragmentManager(), TAG_REQUEST_DIALOG);
    }

    @Override
    public void onDetailedViewPressed(CastItem cast)
    {

    }

    // ---------------RequestDialogFragment-callbacks-----------------------------------------------

    @Override
    public void onRequestSubmitted(Cast.Request request)
    {
        String type = request.getD();
        String count = Integer.toString(request.getCount());
        if (request.getSuccessFrom() != null)
            Bridge.send(Event.CAST_REQUEST_ISSUED.withArgs(type, count, Integer.toString(request.getSuccessFrom())));
        else
            Bridge.send(Event.CAST_REQUEST_ISSUED.withArgs(type, count));
    }
}
