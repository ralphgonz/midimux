package midimux;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.*;

/**
 * Multiplex two MIDI music files. The note values of the second file are scaled
 * and applied to velocity setting of the first file, on a track-by-track basis.
 * The second file is first stretched to match the duration of the first.
 * 
 * Args:
 * 		<song MIDI file name>
 * 		<mux MIDI file name>
 *
 */
public class MidiMux {
	Sequencer sequencer = null;
	Synthesizer synth = null;
	Receiver receiver = null;
	static final int ppb=96; // pulses (ticks) per beat
	int beatsPerMinute = 0;
	double msPerTick = -1;
	long timeZero = -1;
	java.util.Timer beatTimer;
	java.util.Timer synthTimer;
	Sequence songSeq;
	Sequence muxSeq;
	Map<Integer, Integer> songChannels = new HashMap<Integer, Integer>();
	Map<Integer, Integer> muxChannels = new HashMap<Integer, Integer>();
	
	public static void main(String[] args) throws Exception {
		String songFileName = args[0];
		String muxFileName = args[1];
		
		MidiMux mm = new MidiMux();
		
		// Load song file and mux file
		mm.sequencer = MidiSystem.getSequencer();
		mm.sequencer.open();
		mm.songSeq = prepareSequence(songFileName, mm.songChannels);
		mm.muxSeq = prepareSequence(muxFileName, mm.muxChannels);
		mm.remapInstruments();
		
		// Multiplex the songs, setting volume of song tracks from note values of mux tracks
		mm.mux();
		
		// Play the song
		mm.sequencer.setSequence(mm.songSeq);
		mm.sequencer.start();
		
	}
	
	// Side-effect: creates channel map
	// Assumption: one channel per track!
	static Sequence prepareSequence(String fileName, Map<Integer, Integer> seqChannels) throws Exception {
		Sequence seq = MidiSystem.getSequence(new File(fileName));
		
		// Find channel map
		for (int i=0 ; i < seq.getTracks().length ; ++i) {
			Track track = seq.getTracks()[i];
			int ch = -1;
			for (int j=0 ; j<track.size() ; ++j) {
				MidiEvent ev = track.get(j);
				MidiMessage m = ev.getMessage();
		    	if (!isNoteOnMessage(m)) {
					continue;
				}
		    	ch = ((ShortMessage)m).getChannel();
		    	break;
			}
			seqChannels.put(i, ch);
		}
			
		return seq;
	}
	
	// Remap instruments per MIDI channel
	void remapInstruments() {
		List<Integer> programs = new ArrayList<Integer>();
		programs.add(41); // Violin
		programs.add(10); // Glockenspiel
		programs.add(57); // Trumpet
		programs.add(20); // Church Organ
		programs.add(12); // Vibraphone
		programs.add(23); // Harmonica
		programs.add(60); // Muted Trumpet
		programs.add(45); // Tremolo Strings
		programs.add(1); // Acoustic Grand Piano
		programs.add(92); // Choir
		programs.add(53); // Choir Aahs
		programs.add(54); // Voice Oohs
		programs.add(47); // Orchestral Harp
		programs.add(46); // Pizzicato Strings
		programs.add(7); // Harpsichord
		programs.add(76); // Pan Flute
		
		// For each track
		for (int i=0 ; i < songSeq.getTracks().length ; ++i) {
			Track track = songSeq.getTracks()[i];
			int ch = songChannels.get(i);
			if (ch >= 0) {
				MidiMessage m = createProgramChange(ch, programs.get(ch), 100); 
				MidiEvent ev = new MidiEvent(m, 10);
				track.add(ev);
			}
		}
		
	}
	
	void mux() {
		int nSongTracks = songSeq.getTracks().length;
		int nMuxTracks = muxSeq.getTracks().length;
		long songLength = songSeq.getTickLength();
		long muxLength = muxSeq.getTickLength();
		
		// For each track
		for (int i=0 ; i < nSongTracks && i < nMuxTracks ; ++i) {
			Track songTrack = songSeq.getTracks()[i];
			Track muxTrack = muxSeq.getTracks()[i];
			int songCh = songChannels.get(i);

			// First pass to get range of notes in mux sequence
			int minNote = -1;
			int maxNote = -1;
			for (int j=0 ; j<muxTrack.size() ; ++j) {
				MidiEvent ev = muxTrack.get(j);
				MidiMessage m = ev.getMessage();
		    	if (!isNoteOnMessage(m)) {
					continue;
				}
		    	int noteNum = ((ShortMessage)m).getData1();
		    	if (minNote < 0 || noteNum < minNote) {
		    		minNote = noteNum;
		    	}
		    	if (maxNote < 0 || noteNum > maxNote) {
		    		maxNote = noteNum;
		    	}
			}
			
			// For each note change event in the mux sequence
			for (int j=0 ; j<muxTrack.size() ; ++j) {
				MidiEvent ev = muxTrack.get(j);
				MidiMessage m = ev.getMessage();
		    	if (!isNoteOnMessage(m)) {
					continue;
				}
			
		    	// Create event to set volume of original song track using mux track's note number
		    	// Must create an event for each channel on this track
		    	int noteNum = ((ShortMessage)m).getData1();
		    	// Scale velocity between 30 and 127
		    	int vel = (int) ((double)(noteNum - minNote) * 97.0 / (double)(maxNote - minNote)) + 30;
				long ticks = (long) (ev.getTick() * (double)songLength / (double)muxLength);
				MidiMessage muxM = createCcVolumeChange(songCh, vel); 
				MidiEvent muxEv = new MidiEvent(muxM, ticks);
				songTrack.add(muxEv);
			}
		}
		
	}
	
    static boolean isNoteOnMessage(MidiMessage m) {
    	return ((m instanceof ShortMessage) && ((ShortMessage)m).getCommand() == ShortMessage.NOTE_ON);
    }
    
	ShortMessage createMessage(int command, int ch, int v1, int v2) {
		ShortMessage m=new ShortMessage();
		try {
			m.setMessage(command,
					ch,
					v1,
					v2
				);
		} catch (InvalidMidiDataException ex) {
			System.out.println("Invalid MIDI data");
		}
		return m;
	}
	
	ShortMessage createProgramChange(int ch, int p, int vel) {
		return createMessage(ShortMessage.PROGRAM_CHANGE, ch, p, vel);
	}
	
	ShortMessage createCcVolumeChange(int ch, int vel) {
		return createMessage(ShortMessage.CONTROL_CHANGE, ch, 7, vel);
	}
	
	// Use homegrown sequencer, no muxing yet...
/*
	public static void main(String[] args) throws Exception {
		String songFileName = args[0];
		
		MidiMux mm = new MidiMux();
		mm.start();

		List<Beat> songBeats = mm.loadSong(songFileName);
		mm.scheduleBeat(songBeats, 0);
		
		// Get MIDI synth
		mm.synth = MidiSystem.getSynthesizer();
		if (mm.synth == null) {
			throw new MidiUnavailableException();
		}
		mm.synth.open();
		mm.receiver = mm.synth.getReceiver();
		
		// Cleanup
		mm.stop();

	}
	
	void start() {
		timeZero = System.currentTimeMillis();
		setTempo();
		beatTimer = new java.util.Timer("barTimer");
		synthTimer = new java.util.Timer("synthTimer");
	}
	
	void setTempo() {
		beatsPerMinute = 120;
		msPerTick = 1000.0*60.0/((double)beatsPerMinute)/((double)ppb);
	}
	
    void stop() {
    	synthTimer.cancel();
    	beatTimer.cancel();
    	sendAllNotesOff();
    	if (synth != null) {
    		synth.close();
    	}
    }
	
    public void scheduleMessage(MidiMessage m, long ticks) {
		long ms=timeZero + ((long)(ticks * msPerTick));
		try {
			synthTimer.schedule(new SynthTimerTask(m), new Date(ms));
		}
		catch (IllegalArgumentException e) {
			playMessage(m); // play immediately if invalid time
		}
    }

    class SynthTimerTask extends java.util.TimerTask
    {
    	public MidiMessage message;
    	public SynthTimerTask(MidiMessage m) {
    		message = m;
    	}
    	public void run() {
    		playMessage(message);
    	}
    }

    void playMessage(MidiMessage m) {
    	if (receiver == null) {
    		return;
    	}
    	
    	synchronized(receiver) {
    		receiver.send(m, -1);
    	}
    }

	void sendAllNotesOff() {
		if (receiver == null) {
			return;
		}
		
		try {
    		ShortMessage m=new ShortMessage();
			m.setMessage(176, 123, 0); // all channels
			playMessage(m);
		} catch (InvalidMidiDataException e1) {
		}
	}   	
	
	// Schedule messages in beat accurately
    void scheduleBeat(List<Beat> beats, int beatNo) {
    	double msPerBeat = 1000.0*60.0/((double)beatsPerMinute);
    	long elapsed=System.currentTimeMillis() - timeZero;
    	long delay=((long)(beatNo * msPerBeat)) - elapsed;
    	if (delay < 0) {
    		delay = 0;
    	}
    	beatTimer.schedule(new BeatTask(beats, beatNo), delay); 
    }
    
    class BeatTask extends java.util.TimerTask {
    	int beatNo;
    	List<Beat> beats;
    	public BeatTask(List<Beat> beats, int beatNo) {
    		this.beats = beats;
    		this.beatNo = beatNo;
    	}
    	public void run() {
    		// Schedule events in this beat
    		for (MidiEvent ev : beats.get(beatNo).events) {
				long ticks = ev.getTick();
				scheduleMessage(ev.getMessage(), ticks);
    		}
    		
    		// And schedule next beat
    		if (beatNo < beats.size() - 1) {
    			scheduleBeat(beats, beatNo + 1);
    		}
    	}
    }
    
    class Beat {
    	public List<MidiEvent> events = new ArrayList<MidiEvent>();
    }
    
    List<Beat> loadSong(String fileName) {
    	ArrayList<Beat> beats = new ArrayList<Beat>();
        try {
        	Sequence sequence = MidiSystem.getSequence(new File(fileName));
			// ppb = sequence.getResolution();
			Track tracks[] = sequence.getTracks();
			for (Track t : tracks) {
				for (int i=0 ; i<t.size() ; ++i) {
					MidiEvent ev = t.get(i);
					long ticks = ev.getTick();
					MidiMessage m = ev.getMessage();
					// check if time signature or tempo change MetaMessage:
					if (isTempoChangeMessage(m)) {
						continue;
					} else if (isTimeSignatureChangeMessage(m)) {
						continue;
//					} else if (isGroupProgramChangeMessage(m)) {
//						continue;
					}
					int beatNo=(ticks == -1 ? 0 : (int)(ticks / (ppb))); 
					ensureBeatsSize(beats, beatNo + 1);
					beats.get(beatNo).events.add(ev);
				}
			}
        } catch (Exception e) {
        	System.out.println("Failed to read midi file " + fileName);
        }

        return beats;
    }

    void ensureBeatsSize(ArrayList<Beat> list, int size) {
        // Prevent excessive copying while we're adding
        list.ensureCapacity(size);
        while (list.size() < size) {
            list.add(new Beat());
        }
    }
    
	final int TEMPO_MESSAGE = 0x51;
    boolean isTempoChangeMessage(MidiMessage m)
    {
    	if (!(m instanceof MetaMessage))
    		return false;
    	return ((MetaMessage)m).getType() == TEMPO_MESSAGE;
    }
    
    boolean isTimeSignatureChangeMessage(MidiMessage m)
    {
    	if (!(m instanceof MetaMessage))
    		return false;
    	return ((MetaMessage)m).getType() == TIME_SIGNATURE_MESSAGE;   	
    }
    
	final int TIME_SIGNATURE_MESSAGE = 0x58;
    boolean isGroupProgramChangeMessage(MidiMessage m)
    {
    	if (!(m instanceof ShortMessage))
    		return false;
    	if (((ShortMessage)m).getCommand() != ShortMessage.PROGRAM_CHANGE)
    		return false;
    	if (((ShortMessage)m).getChannel() != 0)
    		return false;
    	return true;
    }

*/
}
