package com.example.observe;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;


public class ObserveService extends Service{

	/* Conexión con vista Main */
	
	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	//Comandos para el Main
	static final int MSG_MAIN_UPDATE_UI = 3;
	//Comandos desde el Main
	static final int MSG_SCAN_START_STOP = 4;
	//Método referentes a eventos del main
	static final int MSG_NEW_INTENT = 5;
	static final int MSG_ACTIVITY_RESULT = 6;
	
	
	private ArrayList<Messenger> mMainClientList = new ArrayList<Messenger>();
	
	//Atributos para "run_observe"
	private String tablet_tp_branch;
	private String tablet_tp_tablet;
	private String tablet_server_ip;
	private String tablet_server_port;
	private long tablet_scan_delta;
	private long tablet_scan_time;
	
	
	class MainIncomingHandler extends Handler
	{
		@Override
		public void handleMessage(Message msg)
		{
			Messenger c;
			switch(msg.what)
			{
				case MSG_REGISTER_CLIENT:
					c = msg.replyTo;
					if (!mMainClientList.contains(c))
					{
						Toast.makeText(getApplicationContext(), "Adding client", Toast.LENGTH_SHORT).show();
						mMainClientList.add(c);
					}
					break;
				case MSG_UNREGISTER_CLIENT:
					c = msg.replyTo;
					if (mMainClientList.contains(c))
					{
						Toast.makeText(getApplicationContext(), "Removing client", Toast.LENGTH_SHORT).show();
						mMainClientList.remove(c);
					}
					break;
				case MSG_SCAN_START_STOP:
					//Recibo parámetros para poder funcionar
					tablet_tp_branch = msg.getData().getString("tablet_tp_branch");
					tablet_tp_tablet = msg.getData().getString("tablet_tp_tablet");
					tablet_server_ip = msg.getData().getString("tablet_server_ip");
					tablet_server_port = msg.getData().getString("tablet_server_port");
					tablet_scan_delta = msg.getData().getLong("tablet_scan_delta");
					tablet_scan_time = msg.getData().getLong("tablet_scan_time");
					
					//comienzo y termino la captura de paquetes
					StartStopService();
					break;
				case MSG_NEW_INTENT:
					Intent intent = (Intent)msg.getData().getSerializable("intent");
					NewIntent(intent);
					break;
				case MSG_ACTIVITY_RESULT:
					int requestCode = msg.getData().getInt("requestCode");
					ActivityResult(requestCode);
					break;
				default:
					super.handleMessage(msg);
			}
		}
	}

	/***
	 * Ejecutado cuando se lanza el evento onActivityResult en el Main. 
	 * @param requestCode: parámetro entregado por evento onActivityResult
	 */
	private void ActivityResult(int requestCode) {

		if (requestCode == PREFS_REQ) {
			//Realiza los cambios en el archivo de preferencias compartido: mPreferences
			//por lo que simplemente usa este método para saber que se realizó un cambio en la configuración
			doUpdatePrefs();
			doUpdateUi();
			doUpdateServiceprefs();
		}
	}
	
	/***
	 * Método para replicar conexión usb cuando se ejecuta el evento onNewIntent en el Main
	 * @param intent
	 */
	public void NewIntent(Intent intent) {
		// Replicate USB intents that come in on the single-top task
		mUsbReceiver.onReceive(this, intent);
	}

	
	/***
	 * Permite comenzar y detener la captura de paquetes
	 */
	public void StartStopService()
	{
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
				e.printStackTrace();
			}
		}
	}
	
	final Messenger mMainMessenger = new Messenger(new MainIncomingHandler()); 
	
	@Override
	public IBinder onBind(Intent intent) {
		Toast.makeText(getApplicationContext(), "Binding...", Toast.LENGTH_SHORT).show();
		return mMainMessenger.getBinder();
	}

	/***
	 * Método que envía notificación a la vista para que actualice su interfaz
	 */
	private void doUpdateUi()
	{	
		Toast.makeText(getApplicationContext(), "doUpdateUi en el servidor", Toast.LENGTH_SHORT).show();
		
		for(Messenger c:mMainClientList)
		{
			Message msg = Message.obtain(null, MSG_MAIN_UPDATE_UI, 0, 0);
			Bundle data = new Bundle();
			
			//Agrego los datos para la interfaz
			data.putBoolean("InternetConnection", InternetConnection);
			data.putBoolean("mUsbPresent", mUsbPresent);
			data.putString("mUsbInfo", mUsbInfo);
			data.putBoolean("mLogging", mLogging);
			data.putInt("mLogCount", mLogCount);
			data.putLong("mLogSize", mLogSize);
			data.putBoolean("isRunning", isRunning);
			data.putSerializable("systemState", systemState);
			data.putString("observe_web_message", observe_web_message);
			
			msg.setData(data);
			
			try {
				//Mando mensaje a la itnerfaz para que se actualice
				c.send(msg);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}
	
	/* Fin Conexión con vista Main */


	
	/* Código del servidor */
	
	Context mContext;

	//Archivo importante porque aquí guardamos el 'estado' actual del programa
	//así compartimos parámetros entre el Main, el service y la antena
	SharedPreferences mPreferences;
	
	//Mensaje del servidor observe para la tablet
	private String observe_web_message;
	
	//dice el estado actual del programa
	private observeState systemState = observeState.pause; 
	
	//Indica si tenemos acceso a internet
	private boolean InternetConnection = false;

	//Indica si observe está funcionando
	private boolean isRunning = false;
	
	//Canales que escucho... por defecto todos
	ArrayList<Integer> mChannelList = new ArrayList<Integer>();

	
	/* Fin Código del servidor */
	
	/* Comunicación con la antena */

	//Permiten establecer conexión con USB
	PendingIntent mPermissionIntent;
	//Maneja conexión con el usb... no se usa (?)
	UsbManager mUsbManager;
	
	//Permiten comunicación con la antena
	Messenger mService = null;
	boolean mIsBound = false;
	
	//Mensajes para manejar el estado del usb
	public class deferredUsbIntent {
		UsbDevice device;
		String action;
		boolean extrapermission;
	};
	ArrayList<deferredUsbIntent> mDeferredIntents = new ArrayList<deferredUsbIntent>();
	
	//Permite manejar mensajes del usb
	private BroadcastReceiver broadcastReceiver;

	//FLags de comunicación con la antena
	public static int PREFS_REQ = 0x1001;
	public static final String PREF_CHANNELHOP = "channel_hop";
	public static final String PREF_CHANNEL = "channel_lock";
	public static final String PREF_CHANPREFIX = "ch_";
	public static final String PREF_LOGDIR = "logdir";

	/* Fin Comunicación con la antena */

	/* No deberíamos tener acceso a los botones desde aquí
	private TextView mTextDashUsbSmall, mTextWifiConnection, mTextState, 
	mTextDashFileSmall, mTextDashHardware, mTextSendStatus;
	private TableRow mRowHardware;
	private Button btStartStop;
	*/
	
	//Dirección donde guardo el archivo .cap
	private String mLogDir;
	private File mLogPath = new File("");
	private int mLogCount = 0;
	private long mLogSize = 0;
		
	private boolean mLogging = false, mUsbPresent = false;
	private String mUsbInfo = "";

	@Override
	public void onCreate() {
		super.onCreate();
		
		Toast.makeText(getApplicationContext(), "onCreate() en el servidor", Toast.LENGTH_SHORT).show();
		

		/* ¿Debería dejar este código? ¿qué hace?
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
		*/

		mContext = this;
		//Uso memoria por defecto asignada a la aplicación para guardar y ver las preferencias del usuario
		mPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        //Inicio conexión con la antena
		Intent svc = new Intent(this, PcapService.class);
		//Inicio servidor
		startService(svc);
		//Inicio conexión con el servidor
		doBindService();

		//Pido acceso al USB
		mPermissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(PcapService.ACTION_USB_PERMISSION), 0);
		IntentFilter filter = new IntentFilter(PcapService.ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		mContext.registerReceiver(mUsbReceiver, filter);

		//Añado canales a las preferencias y el directorio
		doUpdatePrefs();
		
		//Creamos directorio en caso de que no exista
		File f = new File(mLogDir);
		if (!f.exists()) {
			f.mkdir();
		}
		
		//Método que chequea el estado de conexión a Internet
		installListener();
		
		//Actualizo interfaz
		doUpdateUi();
	}

	/***
	 * Detiene el loop con la captura de paquetes, envío al servidor y esperar delta_time
	 */
	private void StopObserve()
	{
		//Detener proceso
		if(stopRescanHandler != null)
			stopRescanHandler.removeCallbacks(ReScan);
		isRunning = false;
		systemState = observeState.pause;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();

		mContext.unregisterReceiver(mUsbReceiver);
		doUnbindService();
	}
	
	/***
	 * Envía mensaje a antena para que comience a capturar paquetes por tiempo indefinido. 
	 * @param path: Ruta donde se guarda la captura
	 */
	private void start(String path)
	{
		mLogPath = new File(path);
		doUpdateServiceLogs(mLogPath.toString(), true);
		doUpdateUi();
	}
	
	/***
	 * Permite detener el servidor 
	 */
	Runnable stopServer = new Runnable() {

        @Override
        public void run() {
    		
        	//Indico que detenga la captura
			doUpdateServiceLogs(null, false);
			//Actualizo la interfaz (cambio nombres de los botones)
    		doUpdateUi();
    		
    		//Envío los datos al servidor
    		try {
				send_cap();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
        }
    };

    /***
     * Método para volver a scanear luego de delta time 
     */
	Runnable ReScan = new Runnable() {

        @Override
        public void run() {
    		
        	//Solo hago la llamada si no han apretado stop entremedio
        	if(isRunning)
				try {
					run_observe();
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
        }
    };
    
    private static final String cap_file = "/android.cap";
	/***
     * Captura paquetes. Guarda los resultados en un archivo fijo: mLogDir + "/android.cap"
     * @param time: Tiempo que dura la captura
     * @throws InterruptedException
     */
	private void scan(long time) throws InterruptedException
	{
		systemState = observeState.scanning;
		
		//comienzo servidor
		start(mLogDir + cap_file);
		//thread que maneja la finalización del scaneo
		Handler stopHandler = new Handler();
		stopHandler.postDelayed(stopServer, time);
	}
 
	private static AsyncHttpClient client = new AsyncHttpClient();
	private Handler stopRescanHandler;
	/***
	 * Método para esperar el tiempo apropiado y luego rescanear 
	 */
	private void wait_and_rescan()
	{
		//Espero delta segundo y vuelvo a llamar
		long time = 1000 * tablet_scan_delta;
		stopRescanHandler = new Handler();
		systemState = observeState.waiting;
		stopRescanHandler.postDelayed(ReScan, time);
	}
	
	/***
	 * Envío el archivo cap al servidor. 
	 * @throws FileNotFoundException
	 */
	private void send_cap() throws FileNotFoundException
	{	
		systemState = observeState.sending;
		
		String branch = tablet_tp_branch;
		String tablet = tablet_tp_tablet;
		String ip = tablet_server_ip;
		String port = tablet_server_port;
		String url = ip + ":" + port + "/histories/upload.json";
		
	    //Defino tupla que quiero crear
		RequestParams params = new RequestParams();
	    Map<String, String> map = new HashMap<String, String>();
	    map.put("tp_id_branch", branch);
	    map.put("tp_id_tablet",  tablet);
	    params.put("tablet", map);
	    params.put("cap", new File(mLogDir + cap_file));
	    
	    //Envío mi tupla
	    client.post(url, params, new AsyncHttpResponseHandler() {
	        @Override
	        public void onSuccess(String response) {
	            Log.w("async", "success!!!!");
	            observe_web_message = response + " ok";
	            wait_and_rescan();
	        } 
	        
	        @Override
	        public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] responseBody, Throwable error)
	        {
	        	observe_web_message = statusCode + " fail";
	        	//Detengo observe porque la comunicación falló o lo sigo intentando(?)
	        	//StopObserve();
	        	wait_and_rescan();
	        }


	    });
	}

	/***
	 * Inicia proceso de captura de paquetes, envío y espera.
	 * @throws InterruptedException
	 */
	private void run_observe() throws InterruptedException
	{
		//Obtengo el tiempo
		long time = 1000 * tablet_scan_time; ;
		
		//Escaneo
		scan(time);
	} 
	
	/***
	 * Método para monitorear el estado de conexión a Internet 
	 */
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
	
	/***
	 * Maneja los mensajes que le envía la antena a ObserveServidor 
	 * @author PCAP Android
	 *
	 */
	class IncomingServiceHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Bundle b;
			boolean updateUi = false;

			switch (msg.what) {
			case PcapService.MSG_RADIOSTATE:
				b = msg.getData();
				if (b == null)
					break;

				if (b.getBoolean(UsbSource.BNDL_RADIOPRESENT_BOOL, false)) {
					mUsbPresent = true;
					mUsbInfo = b.getString(UsbSource.BNDL_RADIOINFO_STRING, "No info available");
				} else {
					// Turn off logging
					if (mUsbPresent) 
						doUpdateServiceLogs(mLogPath.toString(), false);

					mUsbPresent = false;
					mUsbInfo = "";
				}

				updateUi = true;

				break;
			case PcapService.MSG_LOGSTATE:
				b = msg.getData();

				if (b == null)
					break;

				if (b.getBoolean(PcapService.BNDL_STATE_LOGGING_BOOL, false)) {
					mLogging = true;

					mLogPath = new File(b.getString(PcapService.BNDL_CMD_LOGFILE_STRING));
					mLogCount = b.getInt(PcapService.BNDL_STATE_LOGFILE_PACKETS_INT, 0);
					mLogSize = b.getLong(PcapService.BNDL_STATE_LOGFILE_SIZE_LONG, 0);
				} else {
					mLogging = false;
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

	/**
	 * Maneja eventos relacionados con la conexión con PCAPService (su conexión y desconexión) 
	 */
	private ServiceConnection mConnection = new ServiceConnection() {
		
		/***
		 * Evento de inicio de la conexión
		 */
		public void onServiceConnected(ComponentName className, IBinder service) {

			//Obs: hacen "mIsBound = true" en doBindService
			mService = new Messenger(service);
			//OJO: Originalmente esto iba en doBindService 
			mIsBound = true;
			
			try {
				//Registra al cliente
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

		/**
		 * Evento de cierre de la conexión
		 */
		public void onServiceDisconnected(ComponentName className) {
			mService = null;
			mIsBound = false;
		}
	};

	/**
	 * Método que inicia conexión con la antena 
	 */
	void doBindService() {

		if (mIsBound) {
			return;
		}

		bindService(new Intent(ObserveService.this, PcapService.class), mConnection, Context.BIND_AUTO_CREATE);
		//Creo que esto debería ir en el onServiceConnected() del mConnection pues recién ahí se sabe
		//si la conexión fue exitosa
		//mIsBound = true;
	}

	/**
	 * Mato el servidor. Creo que nadie llama a este método... ¿merece morir?
	 */
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
		}
	}

	/**
	 * Lee archivo de preferencias para saber qué canales con los que se quieren escuchar.
	 * Por defecto escucha en todos los canales
	 */
	private void doUpdatePrefs() {

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

	/**
	 * Envía a la antena un mensaje diciéndole los canales que quiero que escuche.
	 * Recordar que la clase donde está el código para manejar estos casos es PcapService. 
	 */
	private void doUpdateServiceprefs() {
		if (mService == null)
			return;

		Message msg = Message.obtain(null, PcapService.MSG_RECONFIGURE_PREFS);
		msg.replyTo = mMessenger;

		try {
			mService.send(msg);
		} catch (RemoteException e) { }
	}

	/***
	 * Método que permite comenzar y dejar de escuchar.
	 * @param path: Ruta donde guardo la captura
	 * @param enable: True comienza a escuchar. False deja de escuchar.
	 */
	private void doUpdateServiceLogs(String path, boolean enable) {
		
		//Reacciono solo si el servicio de 'antena' aún está funcionando
		//Esto se setea en 'ServiceConnection mConnection'
		if (mService == null)
			return;

		Bundle b = new Bundle();

		if (enable) { //Quiero comenzar a escuchar
			
			Toast.makeText(getApplicationContext(), "Iniciar scan", Toast.LENGTH_SHORT).show();
			
			//le paso el archivo donde debe escribir
			b.putString(PcapService.BNDL_CMD_LOGFILE_STRING, path);
			//digo que quiero comenzar
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_START_BOOL, true);
		} else {
			//quiero dejar de escuchar
			b.putBoolean(PcapService.BNDL_CMD_LOGFILE_STOP_BOOL, true);
			
			Toast.makeText(getApplicationContext(), "Fin scan", Toast.LENGTH_SHORT).show();
			
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
		}
	}

	/**
	 * Desconecta conexión con la antena
	 */
	void doUnbindService() {
		if (mIsBound) {
			if (mService != null) {
				try {
					//Elimino este cliente del registro en la antena
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

	/**
	 * 
	 * Método que envía instrucciones a la antena referentes a la conexión USB
	 * @param i: Parámetros usb
	 */
	void doSendDeferredIntent(deferredUsbIntent i) {
		Message msg;

		Bundle b = new Bundle();

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

	/**
	 * Maneja respuestas enviadas por el usb
	 */
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
