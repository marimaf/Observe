package com.example.observe;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TableRow;
import android.widget.TextView;

public class MainActivity extends Activity { 
	String LOGTAG = "PcapCapture";

	PendingIntent mPermissionIntent;
	UsbManager mUsbManager;
	Context mContext;

	SharedPreferences mPreferences;

	Messenger mService = null;
	boolean mIsBound = false;

	public class deferredUsbIntent {
		UsbDevice device;
		String action;
		boolean extrapermission;
	};

	ArrayList<deferredUsbIntent> mDeferredIntents = new ArrayList<deferredUsbIntent>();

	private TextView mTextDashUsb, mTextDashUsbSmall, mTextDashFile, 
	mTextDashFileSmall,	mTextManageSmall, mTextDashHardware, mTextScan;
	private TableRow mRowLogShare, mRowManage, mRowHardware, mRowScan;

	private String mLogDir;
	private File mLogPath = new File("");
	private File mOldLogPath;
	private boolean mShareOnStop = false;
	private boolean mLocalLogging = false, mLogging = false, mUsbPresent = false;
	private int mLogCount = 0;
	private long mLogSize = 0;
	private String mUsbType = "", mUsbInfo = "";
	private int mLastChannel = 0;

	public static int PREFS_REQ = 0x1001;

	public static final String PREF_CHANNELHOP = "channel_hop";
	public static final String PREF_CHANNEL = "channel_lock";
	public static final String PREF_CHANPREFIX = "ch_";

	public static final String PREF_LOGDIR = "logdir";

	private boolean mChannelHop;
	private int mChannelLock;
	ArrayList<Integer> mChannelList = new ArrayList<Integer>();

	class IncomingServiceHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Bundle b;
			boolean updateUi = false;

			switch (msg.what) {
			case PcapService.MSG_RADIOSTATE:
				b = msg.getData();

				Log.d(LOGTAG, "Got radio state: " + b);

				if (b == null)
					break;

				if (b.getBoolean(UsbSource.BNDL_RADIOPRESENT_BOOL, false)) {
					mUsbPresent = true;

					mUsbType = b.getString(UsbSource.BNDL_RADIOTYPE_STRING, "Unknown");
					mUsbInfo = b.getString(UsbSource.BNDL_RADIOINFO_STRING, "No info available");
					mLastChannel = b.getInt(UsbSource.BNDL_RADIOCHANNEL_INT, 0);
				} else {
					// Turn off logging
					if (mUsbPresent) 
						doUpdateServiceLogs(mLogPath.toString(), false);

					mUsbPresent = false;
					mUsbType = "";
					mUsbInfo = "";
					mLastChannel = 0;
				}

				updateUi = true;

				break;
			case PcapService.MSG_LOGSTATE:
				b = msg.getData();

				if (b == null)
					break;

				if (b.getBoolean(PcapService.BNDL_STATE_LOGGING_BOOL, false)) {
					mLocalLogging = true;
					mLogging = true;

					mLogPath = new File(b.getString(PcapService.BNDL_CMD_LOGFILE_STRING));
					mLogCount = b.getInt(PcapService.BNDL_STATE_LOGFILE_PACKETS_INT, 0);
					mLogSize = b.getLong(PcapService.BNDL_STATE_LOGFILE_SIZE_LONG, 0);
				} else {
					mLocalLogging = false;
					mLogging = false;

					if (mShareOnStop) {
						Intent i = new Intent(Intent.ACTION_SEND); 
						i.setType("application/cap"); 
						i.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + mOldLogPath)); 
						startActivity(Intent.createChooser(i, "Share Pcap file"));
						mShareOnStop = false;
					}
				}

				updateUi = true;

				break;
			default:
				super.handleMessage(msg);
			}

			if (updateUi)
				doUpdateUi();
		}
	}

	final Messenger mMessenger = new Messenger(new IncomingServiceHandler());

	//Al parecer esta clase ve la conexión con la antena
	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			Log.d(LOGTAG, "mconnection connected");

			mService = new Messenger(service);

			try {
				Message msg = Message.obtain(null, PcapService.MSG_REGISTER_CLIENT);
				msg.replyTo = mMessenger;
				mService.send(msg);

				for (deferredUsbIntent di : mDeferredIntents) 
					doSendDeferredIntent(di);

			} catch (RemoteException e) {
				// Service has crashed before we can do anything, we'll soon be
				// disconnected and reconnected, do nothing
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			mService = null;
			mIsBound = false;
		}
	};

	void doBindService() {
		Log.d(LOGTAG, "binding service");

		if (mIsBound) {
			Log.d(LOGTAG, "already bound");
			return;
		}

		bindService(new Intent(MainActivity.this, PcapService.class), mConnection, Context.BIND_AUTO_CREATE);
		mIsBound = true;
	}

	void doKillService() {
		if (mService == null)
			return;

		if (!mIsBound)
			return;

		Message msg = Message.obtain(null, PcapService.MSG_DIE);
		msg.replyTo = mMessenger;

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			Log.d(LOGTAG, "Failed to send die message: " + e);
		}
	}

	//Lee archivo de preferencias para saber qué canales con los que se quieren escuchar
	//Por defecto escucha en todos los canales
	private void doUpdatePrefs() {
		mChannelHop = mPreferences.getBoolean(PREF_CHANNELHOP, true);
		String chpref = mPreferences.getString(PREF_CHANNEL, "11");
		mChannelLock = Integer.parseInt(chpref);

		if (!mPreferences.contains(PREF_LOGDIR)) {
			SharedPreferences.Editor e = mPreferences.edit();
			e.putString(PREF_LOGDIR, "/mnt/sdcard/pcap");
			e.commit();
		}

		mLogDir = mPreferences.getString(PREF_LOGDIR, "/mnt/sdcard/pcap");

		mChannelList.clear();
		for (int c = 1; c <= 11; c++) {
			if (mPreferences.getBoolean(PREF_CHANPREFIX + Integer.toString(c), true)) {
				mChannelList.add(c);
			}
		}
	}

	//Envía a la antena un mensaje diciéndole los canales que quiero que escuche
	//Recordar que la clase donde está el código para manejar estos casos es PcapService
	private void doUpdateServiceprefs() {
		if (mService == null)
			return;

		Message msg = Message.obtain(null, PcapService.MSG_RECONFIGURE_PREFS);
		msg.replyTo = mMessenger;

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			Log.e(LOGTAG, "Failed to send prefs message: " + e);
		}
	}

	//Método clave para ecuchar y dejar de escuchar!
	//doUpdateServiceLogs(path, true); -> comienzo a escuchar 
	//doUpdateServiceLogs(null, false); -> dejo de escuchar
	//Su funcionamiento se base en servicios por lo que debería seguir funcionando incluso si cambio de aplicación
	private void doUpdateServiceLogs(String path, boolean enable) {
		
		//Reacciono solo si el servicio de 'antena' aún está funcionando
		//Esto se setea en 'ServiceConnection mConnection'
		if (mService == null)
			return;

		Bundle b = new Bundle();

		if (enable) { //Quiero comenzar a escuchar
			//le paso el archivo donde debe escribir
			b.putString(PcapService.BNDL_CMD_LOGFILE_STRING, path);
			//digo que quiero comenzar
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_START_BOOL, true);
		} else {
			//quiero dejar de escuchar
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_STOP_BOOL, true);
		}

		//Configuro el mensaje que enviaré a la antena
		Message msg = Message.obtain(null, PcapService.MSG_COMMAND);
		//Seteo quién manejará la respuesta
		msg.replyTo = mMessenger;
		//Envío mensaje
		msg.setData(b);

		try {
			//intengo ejecutar el servicio
			mService.send(msg);
		} catch (RemoteException e) {
			Log.e(LOGTAG, "Failed to send command message: " + e);
		}
	}

	void doUnbindService() {
		if (mIsBound) {
			if (mService != null) {
				try {
					Message msg = Message.obtain(null, PcapService.MSG_UNREGISTER_CLIENT);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
					// Do nothing
				}
			}
		}

		mService = null;
		mIsBound = false;
	}

	void doSendDeferredIntent(deferredUsbIntent i) {
		Message msg;

		Bundle b = new Bundle();

		// Toast.makeText(mContext, "Sending deferred intent", Toast.LENGTH_SHORT).show();

		msg = Message.obtain(null, PcapService.MSG_USBINTENT);

		b.putParcelable("DEVICE", i.device);
		b.putString("ACTION", i.action);
		b.putBoolean("EXTRA", i.extrapermission);

		msg.setData(b);

		try {
			mService.send(msg);
		} catch (RemoteException e) {
			// nothing
		}
	}

	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (PcapService.ACTION_USB_PERMISSION.equals(action) ||
					UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) ||
					UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				synchronized (this) {
					doBindService();

					deferredUsbIntent di = new deferredUsbIntent();
					di.device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					di.action = action;
					di.extrapermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

					if (mService == null)
						mDeferredIntents.add(di);
					else
						doSendDeferredIntent(di);
				}
			}
		}
	};

	//Actualiza la interfaz gráfica
	// variable 'mUsbPresent' indica si conectó la antena
	private void doUpdateUi() {
		if (!mUsbPresent) {
			mTextDashUsb.setText("No USB device present");
			mTextDashUsbSmall.setVisibility(View.GONE);
			mTextDashUsbSmall.setText("");

		} else {
			mTextDashUsb.setText(mUsbType);
			mTextDashUsbSmall.setText(mUsbInfo);
			mTextDashUsbSmall.setVisibility(View.VISIBLE);
		}

		if (!mLogging) {
			mTextDashFile.setText("Logging inactive");
			mTextDashFileSmall.setText("");
			mTextDashFileSmall.setVisibility(View.GONE);
			mRowLogShare.setClickable(false);
		} else {
			mTextDashFile.setText(mLogPath.getName());
			mTextDashFileSmall.setVisibility(View.VISIBLE);
			mRowLogShare.setClickable(true);
		}

		if (mLogCount > 0 || mLogging) {
			String sz = "0B";

			if (mLogSize < 1024) {
				sz = String.format("%dB", mLogSize);
			} else if (mLogSize < (1024 * 1024)) {
				sz = String.format("%2.2fK", ((float) mLogSize) / 1024);
			} else if (mLogSize < (1024 * 1024 * 1024)) {
				sz = String.format("%5.2fM", ((float) mLogSize) / (1024 * 1024));
			}

			mTextDashFileSmall.setText(sz + ", " + mLogCount + " packets");
		} else {
			mTextDashFileSmall.setText("");
		}

		if (!mLocalLogging && mUsbPresent) {
			mTextScan.setText("Scan");
		} else if (mLocalLogging) {
			mTextScan.setText("Scaneando...");
		}

		doUpdateFilesizes();
	}

	//Método por el que todo parte
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Don't launch a second copy from the USB intent
		if (!isTaskRoot()) {
			final Intent intent = getIntent();
			final String intentAction = intent.getAction(); 
			if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intentAction != null && intentAction.equals(Intent.ACTION_MAIN)) {
				Log.w(LOGTAG, "Main Activity is not the root.  Finishing Main Activity instead of launching.");
				finish();
				return;
			}
		}

		mContext = this;

		//Uso memoria por defecto asignada a la aplicación para guardar y ver las preferencias del usuario
		mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

		setContentView(R.layout.activity_main);

		mTextDashUsb = (TextView) findViewById(R.id.textDashUsbDevice);
		mTextDashUsbSmall = (TextView) findViewById(R.id.textDashUsbSmall);
		mTextDashFile = (TextView) findViewById(R.id.textDashFile);
		mTextDashFileSmall = (TextView) findViewById(R.id.textDashFileSmall);
		mTextManageSmall = (TextView) findViewById(R.id.textManageSmall);
		mTextDashHardware = (TextView) findViewById(R.id.textDashHardware);
		mTextScan = (TextView) findViewById(R.id.textDashScan);

		mRowLogShare = (TableRow) findViewById(R.id.tableRowFile);
		mRowManage = (TableRow) findViewById(R.id.tableRowManage);
		mRowHardware = (TableRow) findViewById(R.id.tableRowHardware);
		//botón para scanear
		mRowScan = (TableRow) findViewById(R.id.tableRowScan);
		
		
        if (Build.MANUFACTURER.equals("motorola")) {
        	mTextDashHardware.setText("Motorola hardware has limitations on USB (special power " +
        			"injectors are needed), check the Help window for a link to more info.");
        	mRowHardware.setVisibility(View.VISIBLE);
        }

        /*Al parecer esto se preocupa de hacer la conexión con la antena
          De hecho creo que esa es al función de la clase PcapService
          Es una clase 'extends Service'; (http://developer.android.com/reference/android/app/Service.html)
          Los servicios son para ejecutar una acción en background... similar a un thread solo que es parte de la
          secuencia principal del programa. Se usan para dejar ejecutando código incluso cuando el usuario no
          interactúa con la aplicación (por lo tanto se usan para hacer cosas en background (Context.startService()) y
          para proveer servicios a otras aplicaciones (Context.bindService()))
          
          Al crear un server se llama a su método onCreate(); y al hacer Context.startService() se llama a onStartCommand()
          se seguirá ejecutando hasta un Context.stopService() o stopSelf()
          
          Si se hace un Context.bindService(), se llama a onBind(Intent) que retorna una interfaz para que sea posible 
          comunicarse con el servidor... en este caso parece que el código lanza el servicio de conexión con la antena
          y además lo accede mediante bindService()
        */
		Intent svc = new Intent(this, PcapService.class);
		// Context.startService() (Activity hereda de context)
		startService(svc);
		doBindService();

		//Pido acceso al USB
		mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(PcapService.ACTION_USB_PERMISSION), 0);

		IntentFilter filter = new IntentFilter(PcapService.ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		mContext.registerReceiver(mUsbReceiver, filter);

		//Las preferencias se refieren a los canales por los que escucho
		//Son del 1 al 11... creo que nos interesa solo el 11 pero por ahora lo dejaré con todos
		//También define el directorio donde se guardan las cosas: "/mnt/sdcard/pcap"
		//La variable mpreference es memoria del tipo SharedPreferences para guardar
		//las preferencias del usuario fácil
		doUpdatePrefs();

		// make the directory on the sdcard
		// Abro-Creo directorio "/mnt/sdcard/pcap" para guardar la info (es memoria externa)
		File f = new File(mLogDir);
		if (!f.exists()) {
			f.mkdir();
		}

		/*
		FilelistFragment list = (FilelistFragment) getFragmentManager().findFragmentById(R.id.fragment_filelist);
		list.registerFiletype("cap", new PcapFileTyper());
		list.setDirectory(new File(mLogDir));
		list.setRefreshTimer(2000);
		list.setFavorites(true);
		list.Populate();
		 */
		// getFragmentManager().beginTransaction().add(R.id.fragment_filelist, list).commit();

		
		//Fila con botón para ir a ver mis archivos guardados
		mRowManage.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(mContext, FilemanagerActivity.class);
				startActivity(i);
			}
		});

		//Fila con mensaje que muestra el número de paquetes escuchados hasta el momento
		//Si se presiona el botón mientras está andando el 'log', permite compartir en forma directa
		//lo que se ha escuchado hasta ahora
		mRowLogShare.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				//método que permite compartir lo que he escuchado hasta el minuto
				doShareCurrent();
			}
		});

		//Botón para Scanear creado por mí :)
		mRowScan.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				//Llamo al método para scanear señales durante 10 segundos
				try {
					scan(10*1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					
				}
			}
		});

		//Actualizo interfaz
		doUpdateUi();
	}

	@Override
	public void onNewIntent(Intent intent) {
		// Replicate USB intents that come in on the single-top task
		mUsbReceiver.onReceive(this, intent);
	}

	@Override
	public void onStop() {
		super.onStop();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		mContext.unregisterReceiver(mUsbReceiver);
		doUnbindService();
	}

	@Override 
	public void onPause() {
		super.onPause();

		// Log.d(LOGTAG, "Onpause");
		// doUnbindService();
	}

	@Override
	public void onResume() {
		super.onResume();

		// Log.d(LOGTAG, "Onresume");
		// doBindService();
	}

	//Actualiza información referente a los canales que el usuario quiere escuchar
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(LOGTAG, "Got activity req " + Integer.toString(requestCode)
                + " result code " + Integer.toString(resultCode));

		if (requestCode == PREFS_REQ) {
			//Lo curioso es que no pareciera usar la información que le llega en data
			//lo que hace es que en la interfaz realiza los cambios en el archivo de 
			//preferencias compartido: mPreferences
			//por lo que simplemente usa este método para saber que se realizó un cambio en la configuración
			doUpdatePrefs();
			doUpdateUi();
			doUpdateServiceprefs();
		}

	}

	protected void doUpdateFilesizes() {
		long ds = FileUtils.countFileSizes(new File(mLogDir), new String[] { "cap" }, 
				false, false, null);
		long nf = FileUtils.countFiles(new File(mLogDir), new String[] { "cap" }, 
				false, false, null);

		String textuse = FileUtils.humanSize(ds);

		mTextManageSmall.setText("Using " + textuse + " in " + nf + " logs");
	}

	protected void doShowHelp() {
		AlertDialog.Builder alert = new AlertDialog.Builder(mContext);

		WebView wv = new WebView(this);
		
		wv.loadUrl("file:///android_asset/html_no_copy/PcapCaptureHelp.html");

		wv.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				Uri uri = Uri.parse(url);
				Intent intent = new Intent(Intent.ACTION_VIEW, uri);
				startActivity(intent);
				
				return true;
			}
		});

		alert.setView(wv);
		
		alert.setNegativeButton("Close", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int id) {
			}
		});
		
		alert.show();
	}
	
	//Método que permite compartir lo que se está escuchando
	//da 3 opciones: dejar de escuchar y compartir, compartir, o cancelar
	protected void doShareCurrent() {
		AlertDialog.Builder alertbox = new AlertDialog.Builder(mContext);

		alertbox.setTitle("Share current pcap?");

		alertbox.setMessage("Sharing the active log can result in truncated log files.  This " +
				"should not cause a problem with most log processors, but for maximum safety, " +
		"you should stop logging first.");

		//Si cancelo no pasa nada
		alertbox.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface arg0, int arg1) {
			}
		});

		//Si comparto me voy a la vista de compartir
		alertbox.setPositiveButton("Share", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface arg0, int arg1) {
				Intent i = new Intent(Intent.ACTION_SEND); 
				i.setType("application/cap"); 
				i.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + mLogPath)); 
				startActivity(Intent.createChooser(i, "Share Pcap file"));
			}
		});

		//Si detengo y comparto, llamo a doUpdateServiceLogs(mLogPath.toString(), false); para dejar de escuchar
		alertbox.setNeutralButton("Stop and Share", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface arg0, int arg1) {
				mShareOnStop = true;
				mOldLogPath = mLogPath;
				doUpdateServiceLogs(mLogPath.toString(), false);
			}
		});

		alertbox.show();

		/*
		 */
	}

	/* -------------------------------------------------------------------------------- */
	/* Nuevos métodos en busca de generar un buen encapsulamiento para el método 'scan' */
	/* -------------------------------------------------------------------------------- */
	
	//Recive una lista con los canales que queremos escuchar (del 1 al 11) 
	private void config(List<Integer> prefs)
	{
		//Actualizo preferencias en archivo interno
		for (int c = 1; c <= 11; c++) 
			mPreferences.edit().putBoolean(PREF_CHANPREFIX + Integer.toString(c), prefs.contains(c));
		//Actualizo nuevas preferencias en todos lados (incluido en la antena)
		doUpdatePrefs();
		doUpdateUi();
		doUpdateServiceprefs();
	}
		
	//Comienzo servidor
	private void start(String path)
	{
		mLocalLogging = true;
		mLogPath = new File(path);
		doUpdateServiceLogs(mLogPath.toString(), true);
		//Actualizo la interfaz (cambio nombres de los botones)
		doUpdateUi();
	}
	
	//Permite detener el servidor
	Runnable stopServer = new Runnable() {

        @Override
        public void run() {
    		
        	mLocalLogging = false;
			doUpdateServiceLogs(null, false);
			//Actualizo la interfaz (cambio nombres de los botones)
    		doUpdateUi();
        }
    };
    
	//Scanea la red durante time mili segundos
	//Guarda los resultados en un archivo fijo: mLogDir + "/android-" + snow + ".cap"
	private void scan(long time) throws InterruptedException
	{
		//comienzo servidor
		start(mLogDir + "/android.cap");
		//thread que maneja la finalización del scaneo
		Handler stopHandler = new Handler();
		stopHandler.postDelayed(stopServer, time);
	}



}
