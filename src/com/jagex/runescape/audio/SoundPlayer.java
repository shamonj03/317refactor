package com.jagex.runescape.audio;

import com.jagex.runescape.sign.signlink;

import javax.sound.midi.*;
import javax.sound.sampled.*;
import java.io.File;

public class SoundPlayer implements Runnable {

    private Sequencer sequencer;
    private int midiVolume;
    private int fadeLevel = 50;
    private String fadeMidi;
    private int fadeVolume;
    private long lastFadeTime;

    public SoundPlayer() {
        try {
            sequencer = MidiSystem.getSequencer();
            if (sequencer != null) {
                sequencer.open();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                musicLoop();
                Thread.sleep(50);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void musicLoop() {
        String midi = signlink.midi;
        if (midi != null) {
            signlink.midi = null;
            if (midi.equals("stop")) {
                stopMidi();
            } else if (midi.equals("voladjust")) {
                adjustMidiVolume(signlink.midiVolume, signlink.midiFade);
            } else {
                if (signlink.midiFade == 1) {
                    fadeMidi = midi;
                    fadeVolume = signlink.midiVolume;
                    // Start fading out from current fadeLevel
                } else {
                    playMidi(midi, signlink.midiVolume);
                }
            }
        }

        String wave = signlink.wave;
        if (wave != null) {
            signlink.wave = null;
            playWave(wave, signlink.wavevol);
        }

        processFade();
    }

    private void stopMidi() {
        if (sequencer != null && sequencer.isRunning()) {
            sequencer.stop();
        }
        fadeLevel = 50;
    }

    private void adjustMidiVolume(int volume, int fade) {
        this.midiVolume = volume;
        if (fade == 1) {
            // If we want to fade to a new volume, but JS doesn't seem to do that.
            // It just sets fade to -(volume/100)
            fadeLevel = -(volume / 100);
        } else {
            fadeLevel = 50; // This seems to be "mute" or "off" in JS logic if it's not midifade
            // Wait, if it's NOT midifade, it should probably be at the requested volume.
            fadeLevel = -(volume / 100);
        }
        updateSequencerVolume();
    }

    private void playMidi(String file, int volume) {
        try {
            if (sequencer != null) {
                sequencer.stop();
                File midiFile = new File(file);
                if (!midiFile.exists()) return;
                
                Sequence sequence = MidiSystem.getSequence(midiFile);
                sequencer.setSequence(sequence);
                sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
                sequencer.start();
                
                this.midiVolume = volume;
                this.fadeLevel = -(volume / 100);
                updateSequencerVolume();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processFade() {
        if (fadeMidi != null) {
            long now = System.currentTimeMillis();
            if (now - lastFadeTime >= 200) {
                lastFadeTime = now;
                fadeLevel++; // Fading out
                updateSequencerVolume();
                if (fadeLevel >= 50) { // Using 50 as silence threshold
                    playMidi(fadeMidi, fadeVolume);
                    fadeMidi = null;
                }
            }
        }
    }

    private void updateSequencerVolume() {
        if (sequencer == null) return;
        
        // fadeLevel 0 (max volume, 0) to 50 (silent, -5000)
        // Map to MIDI 0-127
        int vol = 127 - (fadeLevel * 127 / 50);
        if (vol < 0) vol = 0;
        if (vol > 127) vol = 127;

        try {
            if (sequencer instanceof Synthesizer) {
                Synthesizer synth = (Synthesizer) sequencer;
                for (MidiChannel channel : synth.getChannels()) {
                    if (channel != null) {
                        channel.controlChange(7, vol);
                    }
                }
            } else {
                Receiver receiver = sequencer.getReceiver();
                for (int i = 0; i < 16; i++) {
                    ShortMessage msg = new ShortMessage();
                    msg.setMessage(ShortMessage.CONTROL_CHANGE, i, 7, vol);
                    receiver.send(msg, -1);
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void playWave(String file, int volume) {
        try {
            File soundFile = new File(file);
            if (!soundFile.exists()) return;
            
            AudioInputStream audioIn = AudioSystem.getAudioInputStream(soundFile);
            Clip clip = AudioSystem.getClip();
            clip.open(audioIn);
            
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                // volume is 0 to -10000. 
                // 0 is 1.0 amplitude, -10000 is 0.0001 amplitude? 
                // Actually bgsound volume was centibels (1/100th of a decibel).
                // So -10000 is -100 dB (silence).
                float dB = volume / 100.0f;
                if (dB < gainControl.getMinimum()) dB = gainControl.getMinimum();
                if (dB > gainControl.getMaximum()) dB = gainControl.getMaximum();
                gainControl.setValue(dB);
            }
            
            clip.start();
            // We should probably close the clip when done, but for simplicity...
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
