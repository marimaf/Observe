package com.example.observe;

import java.util.ArrayList;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;


public class ObserveService extends Service{

	/* Conexión con vista Main */
	
	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	//Comandos para el Main
	static final int MSG_MAIN_UPDATE_UI = 3;
	//Comandos desde el Main
	static final int MSG_SCAN_START = 4;
	static final int MSG_SCAN_STOP = 5;
	
	
	private ArrayList<Messenger> mMainClientList = new ArrayList<Messenger>();
	
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
				case MSG_SCAN_START:
					//TODO implementa!!
					//Actualizar preferencias
					//Comensar a escuchar
					break;
				case MSG_SCAN_STOP:
					//TODO implementa!!
					//Dejar de escuchar
					break;
				default:
					super.handleMessage(msg);
			}
		}
	}

	final Messenger mMainMessenger = new Messenger(new MainIncomingHandler()); 
	
	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		Toast.makeText(getApplicationContext(), "Binding...", Toast.LENGTH_SHORT).show();
		return mMainMessenger.getBinder();
	}

	//Métodos comuniación con vista
	private void UpdateMainUI()
	{
		//TODO: Terminar este método
		//1) Actualizar los valores en mPreference (no olvidar commit!)
		//2) renderearlo
		
		for(Messenger c:mMainClientList)
		{
			Message msg = Message.obtain(null, MSG_MAIN_UPDATE_UI, 0, 0); 
			try {
				c.send(msg);
			} catch (RemoteException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	/* Fin Conexión con vista Main */
	
	

}
