package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.ListFragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shows the list of currently-attached USB devices. The actual “connect” logic
 * now lives in {@link #openTerminal(ListItem)}, so we can invoke it from a UI
 * tap *or* programmatically (e.g. after a USB-attach broadcast).
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class DevicesFragment extends ListFragment {

    /*───────────────────────────────┐
     │  Model object for the ListView │
     └───────────────────────────────*/
    static class ListItem {
        UsbDevice device;
        int       port;
        UsbSerialDriver driver;
        ListItem(UsbDevice d, int p, UsbSerialDriver dr) {
            device = d; port = p; driver = dr; }
        @Override public String toString() {
            if (driver == null)
                return String.format("%s (no driver)", device.getDeviceName());
            UsbSerialPort usbPort = driver.getPorts().get(port);
            return String.format( "%s / %s #%d", device.getDeviceName(),
                    usbPort.getClass().getSimpleName(), usbPort.getPortNumber());
        }
    }

    private final List<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 115200;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setRetainInstance(true);

        listAdapter = new ArrayAdapter<ListItem>(requireContext(),
                android.R.layout.simple_list_item_1, listItems) {
            @Override public View getView(int pos, View cv, @NonNull ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                ((TextView) v).setText(Objects.requireNonNull(getItem(pos)).toString());
                return v; }
        };
        setListAdapter(listAdapter);
    }


    @Override public void onResume() {
        super.onResume();
        refresh();
        getListView().postDelayed(this::autoConnectFirstPort, 5000); // 5-s delay
    }

    /*───────────────────────────────────────────────────────────*/
    /* Helper methods the activity can call                      */
    /*───────────────────────────────────────────────────────────*/
    public void refresh() {
        UsbManager mgr = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
        UsbSerialProber def = UsbSerialProber.getDefaultProber();
        UsbSerialProber custom = CustomProber.getCustomProber();

        listItems.clear();
        for (UsbDevice dev : mgr.getDeviceList().values()) {
            UsbSerialDriver drv = def.probeDevice(dev);
            if (drv == null) drv = custom.probeDevice(dev);
            if (drv != null) {
                for (int p = 0; p < drv.getPorts().size(); p++)
                    listItems.add(new ListItem(dev, p, drv));
            } else {
                listItems.add(new ListItem(dev, 0, null));
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    /** Connect to the first entry that actually has a driver. */
    public void autoConnectFirstPort() {
        for (ListItem li : listItems) {
            if (li.driver != null) { openTerminal(li); break; }
        }
    }

    /*───────────────────────────────────────────────────────────*/
    /*  UI callback                                             */
    /*───────────────────────────────────────────────────────────*/
    @Override public void onListItemClick(ListView l, View v, int pos, long id) {
        openTerminal(listItems.get(pos));
    }

    /*───────────────────────────────────────────────────────────*/
    /*  Internal: launch the TerminalFragment                    */
    /*───────────────────────────────────────────────────────────*/
    private void openTerminal(ListItem li) {
        if (li.driver == null) {
            Toast.makeText(getActivity(), "No driver for that device", Toast.LENGTH_SHORT).show();
            return;
        }
        Bundle args = new Bundle();
        args.putInt("device", li.device.getDeviceId());
        args.putInt("port",   li.port);
        args.putInt("baud",   baudRate);
        TerminalFragment tf = new TerminalFragment();
        tf.setArguments(args);
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment, tf, "terminal")
                .addToBackStack(null)
                .commit();
    }
}
