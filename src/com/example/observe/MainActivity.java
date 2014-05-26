package com.example.observe;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.entity.StringEntity;
import org.json.JSONException;
import org.json.JSONObject;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

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
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;


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

	private TextView mTextDashUsbSmall, mTextWifiConnection, mTextState, 
	mTextDashFileSmall, mTextDashHardware, mTextSendStatus;
	private TableRow mRowHardware;
	
	private Button btStartStop;
	
	private observeState systemState = observeState.pause; 
	
	private String mLogDir;
	private File mLogPath = new File("");
	private File mOldLogPath;
	private boolean mShareOnStop = false;
	private boolean mLocalLogging = false, mLogging = false, mUsbPresent = false;
	private int mLogCount = 0;
	private long mLogSize = 0;
	private String mUsbType = "", mUsbInfo = "";
	private int mLastChannel = 0;

	private BroadcastReceiver broadcastReceiver;
	private boolean InternetConnection = false;
	
	//Indica si observe está funcionando
	private boolean isRunning = false;
	
	public static int PREFS_REQ = 0x1001;

	public static final String PREF_CHANNELHOP = "channel_hop";
	public static final String PREF_CHANNEL = "channel_lock";
	public static final String PREF_CHANPREFIX = "ch_";

	public static final String PREF_LOGDIR = "logdir";

	private boolean mChannelHop;
	private int mChannelLock;
	ArrayList<Integer> mChannelList = new ArrayList<Integer>();

	//Actualiza la interfaz gráfica
	// variable 'mUsbPresent' indica si conectó la antena
	private void doUpdateUi() {
		
		//Actualizo conexión a internet
        if (InternetConnection)
        	mTextWifiConnection.setText("Ok");
        else
        	mTextWifiConnection.setText("No Internet access");
        
		if (!mUsbPresent) {
			mTextDashUsbSmall.setText("No USB device present");

		} else {
			mTextDashUsbSmall.setText(mUsbInfo);
			mTextDashUsbSmall.setVisibility(View.VISIBLE);
		}

		if (!mLogging) {
			mTextDashFileSmall.setText("");
			mTextDashFileSmall.setVisibility(View.GONE);
		} else {
			mTextDashFileSmall.setVisibility(View.VISIBLE);
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

		//Dejo actuvo el botón solo si tengo conexión a internet, hay una antena conectada y estoy
		//en waiting o en pause
		btStartStop.setEnabled(InternetConnection && mUsbPresent && 
				(systemState == observeState.pause || systemState == observeState.waiting));
		if(!isRunning)
			btStartStop.setText("Start");
		else
			btStartStop.setText("Stop");
		
		//Actualizo estado de observe
		if(systemState == observeState.pause)
			mTextState.setText("Paused");
		if(systemState == observeState.scanning)
			mTextState.setText("Scanning");
		if(systemState == observeState.sending)
			mTextState.setText("Send data server");
		if(systemState == observeState.waiting)
			mTextState.setText("Waiting");
		
		
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

		btStartStop = (Button) findViewById(R.id.buttonStartStop);

		mTextState = (TextView) findViewById(R.id.textObserveState);
		mTextDashUsbSmall = (TextView) findViewById(R.id.textDashUsbDevice);
		mTextWifiConnection = (TextView) findViewById(R.id.textWifiState);
		mTextDashFileSmall = (TextView) findViewById(R.id.textDashFileSmall);
		mTextDashHardware = (TextView) findViewById(R.id.textDashHardware);
		mTextSendStatus = (TextView) findViewById(R.id.textServer);
		
		mRowHardware = (TableRow) findViewById(R.id.tableRowHardware);
		
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
		
		//Botón de inicio
		btStartStop.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				
				if(isRunning)
				{
					//Detener proceso
					StopObserve();
				}
				else
				{
					//Comenzar proceso
					isRunning = true;
					try {
						run_observe();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		});
		
		installListener();
		
		//Actualizo interfaz
		doUpdateUi();
	}

	private void StopObserve()
	{
		//Detener proceso
		if(stopRescanHandler != null)
			stopRescanHandler.removeCallbacks(ReScan);
		isRunning = false;
		systemState = observeState.pause;
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

	/***
	 * Creo que este método es inútil :S
	 */
	protected void doUpdateFilesizes() {
		long ds = FileUtils.countFileSizes(new File(mLogDir), new String[] { "cap" }, 
				false, false, null);
		long nf = FileUtils.countFiles(new File(mLogDir), new String[] { "cap" }, 
				false, false, null);

		String textuse = FileUtils.humanSize(ds);

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
    		
    		//Envío los datos al servidor
    		try {
				send_cap();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		
    		//Antes aquí llamaba al módulo q esperaba y volvía a escuchar
    		//Ahora espero luego de la respuesta del server
    		//wait_and_rescan()
        }
    };

	//Método para volver a scanear luego de delta time
	Runnable ReScan = new Runnable() {

        @Override
        public void run() {
    		
        	//Solo hago la llamada si no han apretado stop entremedio
        	if(isRunning)
				try {
					run_observe();
				} catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
        }
    };
    
    private static final String cap_file = "/android.cap";
	//Scanea la red durante time mili segundos
	//Guarda los resultados en un archivo fijo: mLogDir + "/android-" + snow + ".cap"
	private void scan(long time) throws InterruptedException
	{
		systemState = observeState.scanning;
		
		//comienzo servidor
		start(mLogDir + cap_file);
		//thread que maneja la finalización del scaneo
		Handler stopHandler = new Handler();
		stopHandler.postDelayed(stopServer, time);
	}

	private static final String URL_HISTORY = "http://10.201.41.171:3000/histories.json"; 
	private static AsyncHttpClient client = new AsyncHttpClient();

	private Handler stopRescanHandler;
	//Método para esperar el tiempo apropiado y luego rescanear
	private void wait_and_rescan()
	{
		//Espero delta segundo y vuelvo a llamar
		long time = 1000 * Long.parseLong(((EditText)findViewById(R.id.editDeltaScan)).getText().toString());
		stopRescanHandler = new Handler();
		systemState = observeState.waiting;
		stopRescanHandler.postDelayed(ReScan, time);
	}
	
	/* Envío el archivo cap al servidor */
	private void send_cap() throws FileNotFoundException
	{	
		systemState = observeState.sending;
		
		String user = ((EditText)findViewById(R.id.editTextUser)).getText().toString();
		String pass = ((EditText)findViewById(R.id.editTextPass)).getText().toString();
		String ip = ((EditText)findViewById(R.id.editTextIpServer)).getText().toString();
		String port = ((EditText)findViewById(R.id.editPortServer)).getText().toString();
		String url = ip + ":" + port + "/histories/upload.json";
		
	    //Defino tupla que quiero crear
		RequestParams params = new RequestParams();
	    Map<String, String> map = new HashMap<String, String>();
	    map.put("usr", user);
	    map.put("pass",  pass);
	    params.put("tablet", map);
	    params.put("cap", new File(mLogDir + cap_file));
	    
	    //Envío mi tupla
	    client.post(url, params, new AsyncHttpResponseHandler() {
	        @Override
	        public void onSuccess(String response) {
	            Log.w("async", "success!!!!");
	            mTextSendStatus.setText(response + " ok");
	            wait_and_rescan();
	        } 
	        
	        @Override
	        public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] responseBody, Throwable error)
	        {
	        	mTextSendStatus.setText(statusCode + " fail");
	        	//Detengo observe porque la comunicación falló o lo sigo intentando(?)
	        	//StopObserve();
	        	wait_and_rescan();
	        }


	    });
	}

	private void run_observe() throws InterruptedException
	{
		//Obtengo el tiempo
		long time = 1000 * Long.parseLong(((EditText)findViewById(R.id.editTimeScan)).getText().toString());
		
		//Escaneo
		scan(time);
		
	} 
	
	private void send_data_json() throws JSONException, UnsupportedEncodingException
	{
		//Defino tupla que quiero crear
		JSONObject params = new JSONObject();
        params.put("id_tablet", "1");
	    params.put("sampling_time(1i)", "2015");
	    params.put("sampling_time(2i)", "4");
	    params.put("sampling_time(3i)", "6");
	    params.put("sampling_time(4i)", "0");
	    params.put("sampling_time(5i)", "5");
	    params.put("congestion", "999");
	    JSONObject total = new JSONObject();
	    total.put("history", params);
	    
	    StringEntity entity = new StringEntity(total.toString());
        
	    
	    //params.put("picture[name]","MyPictureName");
	    //params.put("picture[image]",File(Environment.getExternalStorageDirectory().getPath() + "/Pictures/CameraApp/test.jpg"));
	    
	    //Envío mi tupla
	    client.post(this, URL_HISTORY, entity, "application/json", new AsyncHttpResponseHandler() {
	        @Override
	        public void onSuccess(String response) {
	            Log.w("async", "success!!!!");
	            mTextSendStatus.setText(response + " ok");
	        } 
	        
	        @Override
	        public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] responseBody, Throwable error)
	        {
	        	//mTextScan.setText(statusCode + " fallo");
	        }


	    });
	}
	
	private void send_data()
	{

	    //Defino tupla que quiero crear
		RequestParams params = new RequestParams();
	    Map<String, String> map = new HashMap<String, String>();
	    map.put("id_tablet", "1");
	    map.put("sampling_time(1i)", "2015");
	    map.put("sampling_time(2i)", "4");
	    map.put("sampling_time(3i)", "6");
	    map.put("sampling_time(4i)", "00");
	    map.put("sampling_time(5i)", "05");
	    map.put("congestion", "999");
	    params.put("history", map);

	    
	    //params.put("picture[name]","MyPictureName");
	    //params.put("picture[image]",File(Environment.getExternalStorageDirectory().getPath() + "/Pictures/CameraApp/test.jpg"));
	    
	    //Envío mi tupla
	    client.post(URL_HISTORY, params, new AsyncHttpResponseHandler() {
	        @Override
	        public void onSuccess(String response) {
	            Log.w("async", "success!!!!");
	            mTextSendStatus.setText(response + " ok");
	        } 
	        
	        @Override
	        public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] responseBody, Throwable error)
	        {
	        	//mTextScan.setText(statusCode + " fallo");
	        }


	    });
	}

	//Método para monitorear el estado de conexión a Internet
	private void installListener() {

        if (broadcastReceiver == null) {

            broadcastReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {

                    Bundle extras = intent.getExtras();

                    NetworkInfo info = (NetworkInfo) extras
                            .getParcelable("networkInfo");

                    State state = info.getState();
                    Log.d("InternalBroadcastReceiver", info.toString() + " "
                            + state.toString());

                    if (state == State.CONNECTED) {
                    	//Intenet está OK
                    	InternetConnection = true;

                    } else {
                    	//No tengo internet
                    	InternetConnection = false;
                    }

                }
            };

            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(broadcastReceiver, intentFilter);
            
            doUpdateUi();
        }
    }

	
	
	/* Métodos y clases para la antena */
	
	//Maneja los mensajes que le envía el servidor (actualizaciones del estado de la antena)
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


}
