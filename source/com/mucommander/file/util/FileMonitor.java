package com.mucommander.file.util;

import com.mucommander.Debug;
import com.mucommander.file.AbstractFile;

import java.util.Iterator;
import java.util.WeakHashMap;

/**
 * FileMonitor allows to monitor a file and detect changes in the file's attributes and notify registered
 * {@link FileChangeListener} listeners accordingly.
 *
 * <p>FileMonitor detects attributes changes by polling the file's attributes at a given frequency and comparing their
 * values with the previous ones. If any of the monitored attributes has changed, {@link FileChangeListener#fileChanged(AbstractFile, int)}
 * is called on each of the registered listeners to notify them of the file attributes that have changed.
 * <br>Here's the list of file attributes that can be monitored:
 * <ul>
 *  <li>{@link #DATE_ATTRIBUTE}
 *  <li>{@link #SIZE_ATTRIBUTE}
 *  <li>{@link #PERMISSIONS_ATTRIBUTE}
 *  <li>{@link #IS_DIRECTORY_ATTRIBUTE}
 *  <li>{@link #EXISTS_ATTRIBUTE}
 * </ul>
 *
 * <p>The polling frequency is controlled by the poll period. This parameter determines how often the file's attributes
 * are checked. The lower this period is, the faster changes will be reported to listeners, but also the higher the
 * impact on I/O and CPU. This parameter should be carefully specified to avoid hogging resources excessively.
 *
 * <p>Note that FileMonitor uses file attributes polling because the Java API doesn't currently provide any better way
 * to do detect file changes. If Java ever does provide a callback mechanism for detecting file changes, this class
 * will be modified to take advantage of it. Another possible improvement would be to add JNI hooks for platform-specific
 * filesystem events such as 'inotify' (Linux Kernel), 'kqueue' (BSD, Mac OS X), PAM (Solaris), ...
 *
 * @see FileChangeListener
 * @author Maxence Bernard
 */
public class FileMonitor implements Runnable {

    /** File date attribute, as returned by {@link AbstractFile#getDate()} */
    public final static int DATE_ATTRIBUTE = 1;

    /** File size attribute, as returned by {@link AbstractFile#getSize()} */
    public final static int SIZE_ATTRIBUTE = 2;

    /** File permissions attribute, as returned by {@link AbstractFile#getPermissions()} */
    public final static int PERMISSIONS_ATTRIBUTE = 4;

    /** File 'is directory' attribute, as returned by {@link AbstractFile#isDirectory()} */
    public final static int IS_DIRECTORY_ATTRIBUTE = 8;

    /** File 'exists' attribute, as returned by {@link AbstractFile#exists()} */
    public final static int EXISTS_ATTRIBUTE = 16;

    /** Default attribute set: DATE_ATTRIBUTE */
    public final static int DEFAULT_ATTRIBUTES = DATE_ATTRIBUTE;

    /** Default poll period in milliseconds */
    public final static long DEFAULT_POLL_PERIOD = 10000;


    /** Monitored file */
    private AbstractFile file;
    /** Monitored attributes */
    private int attributes;
    /** Poll period in milliseconds, i.e. the time to elapse between two file attributes polls */
    private long pollPeriod;

    /** The thread that actually does the file attributes polling and event firing */
    private Thread monitorThread;

    /** Registered FileChangeListener instances, stored as weak references */
    private WeakHashMap listeners = new WeakHashMap();


    /**
     * Creates a new FileMonitor that monitors the given file for changes, using the default attribute set (as defined
     * by {@link #DEFAULT_ATTRIBUTES}) and default poll period (as defined by {@link #DEFAULT_POLL_PERIOD}).
     *
     * <p>See the general constructor {@link #FileMonitor(AbstractFile, int, long)} for more information.
     *
     * @param file the AbstractFile to monitor for changes
     */
    public FileMonitor(AbstractFile file) {
        this(file, DEFAULT_ATTRIBUTES, DEFAULT_POLL_PERIOD);
    }

    /**
     * Creates a new FileMonitor that monitors the given file for changes, using the specified attribute set and
     * default poll period as defined by {@link #DEFAULT_POLL_PERIOD}.
     *
     * <p>See the general constructor {@link #FileMonitor(AbstractFile, int, long)} for more information.
     *
     * @param file the AbstractFile to monitor for changes
     * @param attributes the set of attributes to monitor, see constant fields for a list of possible attributes
     */
    public FileMonitor(AbstractFile file, int attributes) {
        this(file, attributes, DEFAULT_POLL_PERIOD);
    }

    /**
     * Creates a new FileMonitor that monitors the given file for changes, using the specified poll period and
     * default attribute set as defined by {@link #DEFAULT_ATTRIBUTES}).
     *
     * <p>See the general constructor {@link #FileMonitor(AbstractFile, int, long)} for more information.
     *
     * @param file the AbstractFile to monitor for changes
     * @param pollPeriod number of milliseconds between two file attributes polls
     */
    public FileMonitor(AbstractFile file, long pollPeriod) {
        this(file, DEFAULT_ATTRIBUTES, pollPeriod);
    }

    /**
     * Creates a new FileMonitor that monitors the given file for changes, using the specified attribute set
     * and poll period.
     *
     * <p>Note that monitoring will only start after {@link #startMonitoring()} has been called.
     *
     * <p>The following attributes can be monitored:
     * <ul>
     *  <li>{@link #DATE_ATTRIBUTE}
     *  <li>{@link #SIZE_ATTRIBUTE}
     *  <li>{@link #PERMISSIONS_ATTRIBUTE}
     *  <li>{@link #IS_DIRECTORY_ATTRIBUTE}
     *  <li>{@link #EXISTS_ATTRIBUTE}
     * </ul>
     * Several attributes can be specified by combining them with the binary OR operator.
     *
     * <p>The poll period specified in the constructor determines how often the file's attributes will be checked.
     * The lower this period is, the faster changes will be reported to registered listeners, but also the higher the
     * impact on I/O and CPU.
     * <br>Note that the time spent for polling is taken into account for the poll period. For example, if the poll
     * period is 1000ms, and polling the file's attributes took 50ms, the next poll will happen in 950ms.
     *
     * @param file the AbstractFile to monitor for changes
     * @param attributes the set of attributes to monitor, see constant fields for a list of possible attributes
     * @param pollPeriod number of milliseconds between two file attributes polls
     */
    public FileMonitor(AbstractFile file, int attributes, long pollPeriod) {
        this.file = file;
        this.attributes = attributes;
        this.pollPeriod = pollPeriod;
    }


    /**
     * Adds the given {@link FileChangeListener} instance to the list of registered listeners.
     *
     * <p>Listeners are stored as weak references so {@link #removeFileChangeListener(FileChangeListener)}
     * doesn't need to be called for listeners to be garbage collected when they're not used anymore.</p>
     *
     * @param listener the FileChangeListener to add to the list of registered listeners.
     */
    public void addFileChangeListener(FileChangeListener listener) {
        listeners.put(listener, null);
    }

    /**
     * Removes the given {@link FileChangeListener} instance to the list of registered listeners.
     *
     * @param listener the FileChangeListener to remove from the list of registered listeners.
     */
    public void removeFileChangeListener(FileChangeListener listener) {
        listeners.remove(listener);
    }


    /**
     * Starts monitoring the monitored file in a dedicated thread. Does nothing if monitoring has already been started
     * and not stopped yet. Calling this method after {@link #stopMonitoring()} has been called will resume monitoring.

     * <p>Once started, the monitoring thread will check for changes in the monitored file attributes specified in
     * the constructor, and call registered {@link FileChangeListener} instances whenever a change in one or several
     * attributes has been detected. The poll period specified in the constructor determines how often the file's
     * attributes will be checked.
     *
     * <p>Monitoring will keep monitoring the file until {@link #stopMonitoring()} is called, even if the monitored
     * file doesn't exist anymore. Thus, it is important not to forget to call {@link #stopMonitoring()} when monitoring
     * is not needed anymore, in order to prevent unnecessary resource hogging.
     */
    public void startMonitoring() {
        // No synchronization performed here so if this method is called multiple times simultaneously from different
        // threads, bad things can occur.
        if(monitorThread ==null) {
            monitorThread = new Thread(this);
            monitorThread.start();
        }
    }

    /**
     * Stops monitoring the monitored file. Does nothing if monitoring has not yet been started.
     */
    public void stopMonitoring() {
        monitorThread = null;
    }

    /**
     * Returns <code>true</code> if FileMonitor is currently monitoring the file.
     */
    public boolean isMonitoring() {
        return monitorThread!=null;
    }


    /**
     * Notifies all registered FileChangeListener instances that the monitored file has changed, specifying which
     * file attributes have changed.
     *
     * @param changedAttributes the set of attributes that have changed
     */
    private void fireFileChangeEvent(int changedAttributes) {
        if(Debug.ON) Debug.trace("firing an event to registered listeners, changed attributes="+changedAttributes);

        // Iterate on all listeners
        Iterator iterator = listeners.keySet().iterator();
        while(iterator.hasNext())
            ((FileChangeListener)iterator.next()).fileChanged(file, changedAttributes);
    }

    
    /////////////////////////////
    // Runnable implementation //
    /////////////////////////////

    public void run() {
        Thread thisThread = monitorThread;

        long lastDate = (attributes&DATE_ATTRIBUTE)!=0?file.getDate():0;
        long lastSize = (attributes&SIZE_ATTRIBUTE)!=0?file.getSize():0;
        int lastPermissions = (attributes&PERMISSIONS_ATTRIBUTE)!=0?file.getPermissions():0;
        boolean lastIsDirectory = (attributes&IS_DIRECTORY_ATTRIBUTE)!=0?file.isDirectory():false;
        boolean lastExists = (attributes&EXISTS_ATTRIBUTE)!=0?file.exists():false;

        long now;
        int changedAttributes;

        long tempLong;
        int tempInt;
        boolean tempBool;

        while(monitorThread ==thisThread) {
            changedAttributes = 0;
            now = System.currentTimeMillis();

            if((attributes&DATE_ATTRIBUTE)!=0) {
                if((tempLong=file.getDate())!=lastDate) {
                    lastDate = tempLong;
                    changedAttributes |= DATE_ATTRIBUTE;
                }
            }

            if(monitorThread ==thisThread && (attributes&SIZE_ATTRIBUTE)!=0) {
                if((tempLong=file.getSize())!=lastSize) {
                    lastSize = tempLong;
                    changedAttributes |= SIZE_ATTRIBUTE;
                }
            }

            if(monitorThread ==thisThread && (attributes&PERMISSIONS_ATTRIBUTE)!=0) {
                if((tempInt=file.getPermissions())!=lastPermissions) {
                    lastPermissions = tempInt;
                    changedAttributes |= PERMISSIONS_ATTRIBUTE;
                }
            }

            if(monitorThread ==thisThread && (attributes& IS_DIRECTORY_ATTRIBUTE)!=0) {
                if((tempBool=file.isDirectory())!=lastIsDirectory) {
                    lastIsDirectory = tempBool;
                    changedAttributes |= IS_DIRECTORY_ATTRIBUTE;
                }
            }

            if(monitorThread ==thisThread && (attributes&EXISTS_ATTRIBUTE)!=0) {
                if((tempBool=file.exists())!=lastExists) {
                    lastExists = tempBool;
                    changedAttributes |= EXISTS_ATTRIBUTE;
                }
            }

            if(changedAttributes!=0)
                fireFileChangeEvent(changedAttributes);

            // Get some well-deserved rest: sleep for the specified poll period minus the time we spent
            // for this iteration
            try {
                Thread.sleep(Math.max(pollPeriod-(System.currentTimeMillis()-now), 0));
            }
            catch(InterruptedException e) {
            }
        }
    }


    /////////////////
    // Test method //
    /////////////////

    public static void main(String args[]) {
        final AbstractFile file = com.mucommander.file.FileFactory.getFile(args[0]);

        FileMonitor fm = new FileMonitor(file, DATE_ATTRIBUTE|SIZE_ATTRIBUTE|PERMISSIONS_ATTRIBUTE|IS_DIRECTORY_ATTRIBUTE|EXISTS_ATTRIBUTE, 1000);

        FileChangeListener listener = new FileChangeListener() {
            public void fileChanged(AbstractFile file, int changedAttributes) {
                System.out.println("File attributes changed:"+changedAttributes);
                if((changedAttributes&DATE_ATTRIBUTE)!=0)
                    System.out.println("\t+ date");
                if((changedAttributes&SIZE_ATTRIBUTE)!=0)
                    System.out.println("\t+ size");
                if((changedAttributes&PERMISSIONS_ATTRIBUTE)!=0)
                    System.out.println("\t+ permissions");
                if((changedAttributes&IS_DIRECTORY_ATTRIBUTE)!=0)
                    System.out.println("\t+ isDirectory");
                if((changedAttributes&EXISTS_ATTRIBUTE)!=0)
                    System.out.println("\t+ exists");
            }
        };

        fm.addFileChangeListener(listener);

        fm.startMonitoring();
        
        try {
            Thread.sleep(Integer.MAX_VALUE);
        }
        catch(InterruptedException e) {
        }
    }
}