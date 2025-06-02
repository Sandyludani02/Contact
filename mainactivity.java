package com.example.phonecontacttracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String CHANNEL_ID = "phone_tracker_channel";
    private static final String TAG = "PhoneContactTracker";

    private ContactDatabase db;
    private ListView contactsListView;
    private SMSReceiver smsReceiver;
    private CallReceiver callReceiver;

    // Daftar izin yang diperlukan
    private final String[] requiredPermissions = {
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inisialisasi database
        db = Room.databaseBuilder(getApplicationContext(),
                ContactDatabase.class, "contact-database").build();

        // Inisialisasi UI
        contactsListView = findViewById(R.id.contactsListView);
        EditText nameEditText = findViewById(R.id.nameEditText);
        EditText phoneEditText = findViewById(R.id.phoneEditText);
        Button addButton = findViewById(R.id.addButton);

        // Setup receiver
        smsReceiver = new SMSReceiver();
        callReceiver = new CallReceiver();

        // Button untuk menambah kontak
        addButton.setOnClickListener(v -> {
            String name = nameEditText.getText().toString();
            String phone = phoneEditText.getText().toString();

            if (!name.isEmpty() && !phone.isEmpty()) {
                Executors.newSingleThreadExecutor().execute(() -> {
                    db.contactDao().insert(new Contact(name, phone, ""));
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Kontak ditambahkan", Toast.LENGTH_SHORT).show();
                        nameEditText.setText("");
                        phoneEditText.setText("");
                        loadContacts();
                    });
                });
            }
        });

        // Cek dan minta izin
        if (checkPermissions()) {
            setupReceivers();
            loadContacts();
        } else {
            requestPermissions();
        }

        // Buat channel notifikasi
        createNotificationChannel();
    }

    private void loadContacts() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Contact> contacts = db.contactDao().getAllContacts();
            List<Map<String, String>> data = new ArrayList<>();
            
            for (Contact contact : contacts) {
                Map<String, String> map = new HashMap<>();
                map.put("name", contact.name);
                map.put("phone", contact.phoneNumber);
                data.add(map);
            }
            
            runOnUiThread(() -> {
                SimpleAdapter adapter = new SimpleAdapter(
                        this,
                        data,
                        android.R.layout.simple_list_item_2,
                        new String[]{"name", "phone"},
                        new int[]{android.R.id.text1, android.R.id.text2});
                contactsListView.setAdapter(adapter);
            });
        });
    }

    private boolean checkPermissions() {
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkPermissions()) {
                setupReceivers();
                loadContacts();
            } else {
                Toast.makeText(this, "Izin diperlukan untuk aplikasi berfungsi", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setupReceivers() {
        // Daftarkan receiver untuk SMS
        IntentFilter smsFilter = new IntentFilter();
        smsFilter.addAction("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(smsReceiver, smsFilter);

        // Daftarkan receiver untuk panggilan
        IntentFilter callFilter = new IntentFilter();
        callFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(callReceiver, callFilter);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Panggilan dan SMS",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifikasi untuk panggilan dan SMS masuk");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String message, String phoneNumber) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("phoneNumber", phoneNumber);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify((int) System.currentTimeMillis(), notification);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister receiver saat aplikasi ditutup
        try {
            unregisterReceiver(smsReceiver);
            unregisterReceiver(callReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver tidak terdaftar");
        }
    }

    // BroadcastReceiver untuk SMS
    public class SMSReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.provider.Telephony.SMS_RECEIVED")) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    try {
                        Object[] pdus = (Object[]) bundle.get("pdus");
                        if (pdus != null) {
                            for (Object pdu : pdus) {
                                SmsMessage sms;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    sms = SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
                                } else {
                                    sms = SmsMessage.createFromPdu((byte[]) pdu);
                                }
                                
                                String phoneNumber = sms.getDisplayOriginatingAddress();
                                String message = sms.getMessageBody();
                                
                                Executors.newSingleThreadExecutor().execute(() -> {
                                    Contact contact = db.contactDao().getContactByNumber(formatPhoneNumber(phoneNumber));
                                    String displayName = contact != null ? contact.name : phoneNumber;
                                    
                                    runOnUiThread(() -> showNotification(
                                            "Pesan dari " + displayName,
                                            message,
                                            phoneNumber));
                                });
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error menerima SMS: " + e.getMessage());
                    }
                }
            }
        }
    }

    // BroadcastReceiver untuk panggilan
    public class CallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            
            if (state != null && state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                String phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                
                if (phoneNumber != null) {
                    Executors.newSingleThreadExecutor().execute(() -> {
                        Contact contact = db.contactDao().getContactByNumber(formatPhoneNumber(phoneNumber));
                        String displayName = contact != null ? contact.name : phoneNumber;
                        
                        runOnUiThread(() -> showNotification(
                                "Panggilan Masuk",
                                "Dari: " + displayName,
                                phoneNumber));
                    });
                }
            }
        }
    }

    // Format nomor telepon
    private String formatPhoneNumber(String number) {
        if (number == null) return "";
        
        // Hapus semua karakter non-digit
        number = number.replaceAll("[^0-9]", "");
        
        // Normalisasi nomor Indonesia
        if (number.startsWith("62")) {
            number = "0" + number.substring(2);
        } else if (number.startsWith("+62")) {
            number = "0" + number.substring(3);
        }
        
        return number;
    }

    // Entity dan Database Room
    @Entity(tableName = "contacts")
    public static class Contact {
        @PrimaryKey(autoGenerate = true)
        public int id;
        public String name;
        public String phoneNumber;
        public String notes;

        public Contact(String name, String phoneNumber, String notes) {
            this.name = name;
            this.phoneNumber = phoneNumber;
            this.notes = notes;
        }
    }

    @Dao
    public interface ContactDao {
        @Insert
        void insert(Contact contact);
        
        @Query("SELECT * FROM contacts WHERE phoneNumber = :number")
        Contact getContactByNumber(String number);
        
        @Query("SELECT * FROM contacts")
        List<Contact> getAllContacts();
    }

    @Database(entities = {Contact.class}, version = 1)
    public abstract static class ContactDatabase extends RoomDatabase {
        public abstract ContactDao contactDao();
    }
}
