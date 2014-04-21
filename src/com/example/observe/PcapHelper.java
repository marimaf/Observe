package com.example.observe;

import java.io.IOException;

public class PcapHelper {
	static {
		System.loadLibrary("pcap");
	}

	public static native int countPcapFile(String path, int max) throws IOException;
	
}