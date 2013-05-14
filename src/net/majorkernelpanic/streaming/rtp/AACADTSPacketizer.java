/*
 * Copyright (C) 2011-2013 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.rtp;

import java.io.IOException;

import android.util.Log;

/**
 *   
 *   RFC 3640.  
 *
 *   This packetizer must be fed with an InputStream containing ADTS AAC. 
 *   AAC will basically be rewrapped in an RTP stream and sent over the network.
 *   This packetizer only implements the aac-hbr mode (High Bit-rate AAC) and
 *   each packet only carry a single and complete AAC access unit.
 * 
 */
public class AACADTSPacketizer extends AbstractPacketizer implements Runnable {

	private final static String TAG = "AACADTSPacketizer";

	// Maximum size of RTP packets
	private final static int MAXPACKETSIZE = 1400;
	
	private Thread t;
	private int samplingRate = 8000;
	private Statistics stats = new Statistics();

	public AACADTSPacketizer() throws IOException {
		super();
	}

	public void start() {
		if (t==null) {
			t = new Thread(this);
			t.start();
		}
	}

	public void stop() {
		try {
			is.close();
		} catch (IOException ignore) {}
		t.interrupt();
		t = null;
	}

	public void setSamplingRate(int samplingRate) {
		this.samplingRate = samplingRate;
	}
	
	public void run() {

		// "A packet SHALL carry either one or more complete Access Units, or a
		// single fragment of an Access Unit.  Fragments of the same Access Unit
		// have the same time stamp but different RTP sequence numbers.  The
		// marker bit in the RTP header is 1 on the last fragment of an Access
		// Unit, and 0 on all other fragments." RFC 3640
		
		// Adts header fields that we need to parse
		boolean protection;
		int frameLength, sum, length, nbau, nbpk;
		long oldtime = System.nanoTime(), now = oldtime, delta = 5000, measured, lastmeasured = 5000, expected, interval = 0;

		stats.init(1024*1000000000/samplingRate);
		
		try {
			while (!Thread.interrupted()) {

				// Synchronisation: ADTS packet starts with 12bits set to 1
				while (true) {
					if ( (is.read()&0xFF) == 0xFF ) {
						buffer[rtphl+1] = (byte) is.read();
						if ( (buffer[rtphl+1]&0xF0) == 0xF0) break;
					}
				}

				// Parse adts header (ADTS packets start with a 7 or 9 byte long header)
				is.read(buffer,rtphl+2,5);
				
				// The protection bit indicates whether or not the header contains the two extra bytes
				protection = (buffer[rtphl+1]&0x01)>0 ? true : false;
				frameLength = (buffer[rtphl+3]&0x03) << 11 | 
						(buffer[rtphl+4]&0xFF) << 3 | 
						(buffer[rtphl+5]&0xFF) >> 5 ;
				frameLength -= (protection ? 7 : 9);
				
				// Number of AAC frames in the ADTS frame
				nbau = (buffer[rtphl+6]&0x03) + 1;
				
				// The number of RTP packets that will be sent for this ADTS frame
				nbpk = frameLength/MAXPACKETSIZE + 1;
				
				// Read CRS if any
				if (!protection) is.read(buffer,rtphl,2);

				// We update the RTP timestamp
				ts +=  1024; //stats.average()*samplingRate/1000000000;
				socket.updateTimestamp(ts);
				
				// We send one RTCP Sender Report every 5 secs
				if (delta>5000) {
					delta = 0;
					report.setNtpTimestamp(now);
					report.setRtpTimestamp(ts);
					report.send();
				}
				
				sum = 0;
				while (sum<frameLength) {

					// Read frame
					if (frameLength-sum > MAXPACKETSIZE-rtphl-4) {
						length = MAXPACKETSIZE-rtphl-4;
					}
					else {
						length = frameLength-sum;
						socket.markNextPacket();
					}
					sum += length;
					is.read(buffer,rtphl+4, length);

					// AU-headers-length field: contains the size in bits of a AU-header
					// 13+3 = 16 bits -> 13bits for AU-size and 3bits for AU-Index / AU-Index-delta 
					// 13 bits will be enough because ADTS uses 13 bits for frame length
					buffer[rtphl] = 0;
					buffer[rtphl+1] = 0x10; 

					// AU-size
					buffer[rtphl+2] = (byte) (frameLength>>5);
					buffer[rtphl+3] = (byte) (frameLength<<3);

					// AU-Index
					buffer[rtphl+3] &= 0xF8;
					buffer[rtphl+3] |= 0x00;

					//Log.d(TAG,"frameLength: "+frameLength+" protection: "+protection+ " length: "+length);
										
					send(rtphl+4+length);

				}

				// We wait a little to avoid sending to many packets too quickly
				now = System.nanoTime();
				interval = now-oldtime;
				measured = interval/1000000;
				stats.push(interval);
				delta += measured;
				oldtime = now;
				expected = nbau*1024*1000 / (nbpk*samplingRate);
				//Log.d(TAG,"expected: "+ expected + " measured: "+measured);
				measured -= lastmeasured<2*expected/3 ? 2*expected/3-lastmeasured : 0;
				lastmeasured = measured;
				if (measured<2*expected/3) {
					Thread.sleep( 2*expected/3-measured );
				}
				
			}
		} catch (IOException e) {
			// Ignore
		} catch (ArrayIndexOutOfBoundsException e) {
			Log.e(TAG,"ArrayIndexOutOfBoundsException: "+(e.getMessage()!=null?e.getMessage():"unknown error"));
			e.printStackTrace();
		} catch (InterruptedException ignore) {}

		Log.d(TAG,"AAC packetizer stopped !");
		
	}

}
