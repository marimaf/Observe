package com.example.observe;


import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;


public class MainActivity extends Activity {
	
	/* Código para conectarse con ObserveService */
	Messenger mObserveService = null;
	boolean mObserveBound = false;
	
	/**
	 * Handler que maneja recepción de mensajes de parte del servidor
	 * @author rodrigo
	 *
	 */
	class IncomingHandler extends Handler
	{
		/**
		 * Define qué hacer cuando servidor envía un mensaje
		 */
		@Override
		public void handleMessage(Message msg)
		{
			switch(msg.what)
			{
				case ObserveService.MSG_MAIN_UPDATE_UI:
					//Actualizamos interfaz gráfica
					doUpdateUi(msg.getData());
					break;
				default:
					super.handleMessage(msg);
			}
		}
	}
	final Messenger mObserveMessenger = new Messenger(new IncomingHandler()); 
	
	/**
	 * Maneja estado de la conexión
	 */
	private ServiceConnection mObserveConnection = new ServiceConnection()
	{
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mObserveService = new Messenger(service);
			mObserveBound = true;

			//Envío mensaje al servidor
			Message message = Message.obtain(null, ObserveService.MSG_REGISTER_CLIENT, 0, 0);
			message.replyTo = mObserveMessenger; 
			try {
				mObserveService.send(message);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mObserveService = null;
			mObserveBound = false;
		}
	};
	
	/*Fin Código para conectarse con ObserveService */

	Context mContext;

	//Objetos de la interfaz
	private TextView mTextDashUsbSmall, mTextWifiConnection, mTextState, 
	mTextDashFileSmall, mTextDashHardware, mTextSendStatus;
	private TableRow mRowHardware;
	
	//Único botón de la aplicación
	private Button btStartStop;

	/**
	 * Actualiza la interfaz gráfica 
	 * @param data: Son los atributos de control entregados desde ObserveService
	 */
	private void doUpdateUi(Bundle data) {
		
		Toast.makeText(getApplicationContext(), "doUpdateUi en el cliente", Toast.LENGTH_SHORT).show();
		
		//Extraemos datos del mensaje
		boolean InternetConnection = data.getBoolean("InternetConnection");
		boolean mUsbPresent = data.getBoolean("mUsbPresent");
		String mUsbInfo = data.getString("mUsbInfo");
		boolean mLogging = data.getBoolean("mLogging");
		int mLogCount = data.getInt("mLogCount");
		long mLogSize = data.getLong("mLogSize");
		boolean isRunning = data.getBoolean("isRunning");
		observeState systemState = (observeState)data.getSerializable("systemState");
		String observe_web_message = data.getString("observe_web_message");
		
		//Actualizo mensaje estado red
		mTextSendStatus.setText(observe_web_message);
		
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
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Don't launch a second copy from the USB intent
		if (!isTaskRoot()) {
			final Intent intent = getIntent();
			final String intentAction = intent.getAction(); 
			if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intentAction != null && intentAction.equals(Intent.ACTION_MAIN)) {
				finish();
				return;
			}
		}

		mContext = this;

		//Asigno variables de la interfaz gráfica
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

        //Creo conexión con ObserveService
		Intent intent = new Intent(this,ObserveService.class);
		if(!mObserveBound)
			bindService(intent,mObserveConnection,Context.BIND_AUTO_CREATE);
		
		//Botón de inicio
		btStartStop.setOnClickListener(new View.OnClickListener() {
			
			/**
			 * Realiza llamada el servidor para que inicie o termine el proceso de observe
			 */
			@Override
			public void onClick(View v) {
				
				if(mObserveBound)
				{
					//Enviamos comando al ObserveService
					Message message = Message.obtain(null, ObserveService.MSG_SCAN_START_STOP, 0, 0);
					
					//Agregamos al mensaje los textos de la interfaz gráfica (la confguración)
					Bundle data = new Bundle();
					data.putString("tablet_tp_branch", ((EditText)findViewById(R.id.editTextIdBranch)).getText().toString());
					data.putString("tablet_tp_tablet", ((EditText)findViewById(R.id.editTextIdTablet)).getText().toString());
					data.putString("tablet_server_ip", ((EditText)findViewById(R.id.editTextIpServer)).getText().toString());
					data.putString("tablet_server_port", ((EditText)findViewById(R.id.editPortServer)).getText().toString());
					data.putLong("tablet_scan_delta", Long.parseLong(((EditText)findViewById(R.id.editDeltaScan)).getText().toString()));
					data.putLong("tablet_scan_time", Long.parseLong(((EditText)findViewById(R.id.editTimeScan)).getText().toString()));
					message.setData(data);
					
					//Envío mensaje
					try {
						mObserveService.send(message);
					} catch (RemoteException e) {
						e.printStackTrace();
					}	
				}
			}
		});
	}

	@Override
	public void onNewIntent(Intent intent) {
		
		//TODO: Aquí deberíamos mandar un mensaje a ObserveService: MSG_NEW_INTENT
		//El problema es que no podemos enviar como parámetro un Intent (no es serializable)
		//por el momento no haremos nada pues se trata de un caso muy particular (cuando se 
		//inicia por segunda vez observe)
		
		/*
		if(mObserveBound){
			//Enviamos comando al ObserveService
			Message message = Message.obtain(null, ObserveService.MSG_NEW_INTENT, 0, 0);
			
			//Agregamos al mensaje los textos de la interfaz gráfica (la confguración)
			Bundle data = new Bundle();
			data.putSerializable("intent", intent);
			message.setData(data);
			
			//Envío mensaje
			try {
				mObserveService.send(message);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
		*/
		
	}

	@Override
	public void onStop() {
		super.onStop();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		if(mObserveBound)
		{
			Message msg = Message.obtain(null, ObserveService.MSG_UNREGISTER_CLIENT);
			msg.replyTo = mObserveMessenger;
			try {
				mObserveService.send(msg);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			
			unbindService(mObserveConnection);
		}

		mObserveService = null;
		mObserveBound = false;
	}

	@Override 
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
	}

	/**
	 * Actualiza información referente a los canales que el usuario quiere escuchar 
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		
		if(mObserveBound){
			//Enviamos comando al ObserveService
			Message message = Message.obtain(null, ObserveService.MSG_ACTIVITY_RESULT, 0, 0);
			
			//Agregamos al mensaje los textos de la interfaz gráfica (la confguración)
			Bundle s_data = new Bundle();
			s_data.putInt("requestCode", requestCode);
			message.setData(s_data);
			
			//Envío mensaje
			try {
				mObserveService.send(message);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}
	}


}
