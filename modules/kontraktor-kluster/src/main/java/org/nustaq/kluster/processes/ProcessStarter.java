package org.nustaq.kluster.processes;

import com.beust.jcommander.JCommander;
import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.Promise;
import org.nustaq.kontraktor.remoting.base.ConnectableActor;
import org.nustaq.kontraktor.remoting.tcp.TCPConnectable;
import org.nustaq.kontraktor.remoting.tcp.TCPNIOPublisher;
import org.nustaq.kontraktor.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created by ruedi on 16/04/16.
 */
public class ProcessStarter extends Actor<ProcessStarter> {

    private static final int TERMINATED = 0;
    private static final int STARTED = 1;
    private static final int SHUTDOWN = 2;
    private static final int RESYNC = 3;
    private static final int RESYNC_RESP = 4;
    private static final int SOFT_SYNC = 5;

    Map<String,StarterDesc> siblings;
    ConnectableActor primarySibling;
    StarterDesc primaryDesc;
    String id;
    String name;
    Map<String,ProcessInfo> processes = new HashMap<>();
    int pids = 1;
    StarterArgs options;

    public void init( StarterArgs options ) {
        this.options = options;
        siblings = new HashMap<>();
        id = UUID.randomUUID().toString();
        if ( options.getName() == null ) {
            try {
                this.name = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                Log.Error(this,e);
                this.name = "unknown";
            }
        } else {
            name = options.getName();
        }
        final String siblingHost = options.getSiblingHost();
        if ( siblingHost != null ) {
            final int siblingPort = options.getSiblingPort();
            primarySibling = new TCPConnectable(ProcessStarter.class, siblingHost, siblingPort);
            cycle();
        }
    }

    void initPrimary() {
        try {
            ProcessStarter sibling =
                (ProcessStarter) primarySibling
                    .connect(
                        (x, y) -> System.out.println("client disc " + x),
                        act -> {
                            System.out.println("act " + act);
                            siblings.clear();
                            primaryDesc = null;
                        }
                    ).await();
            primaryDesc = sibling.getInstanceDesc().await();
            siblings.put(primaryDesc.getId(),primaryDesc);
            System.out.println("primary sibling connected "+primaryDesc);
            distribute(SOFT_SYNC,null,null);
        } catch (Throwable e) {
            System.out.println("failed to connect primary "+primarySibling);
        }
    }

    public void distribute( int mid, Object arg, HashSet<String> visited ) {
        if ( visited == null ) {
            visited = new HashSet<>();
        } else if ( visited.contains(id) )
            return;
        visited.add(id);
        processMessage(mid, arg);
        final HashSet<String> finalVisited = visited;
        siblings.values().forEach( desc -> {
            if ( ! finalVisited.contains(desc.getId()) )
                desc.getRemoteRef().distribute(mid,arg, finalVisited);
        });
    }

    protected void processMessage(int mid, Object arg) {
        switch ( mid ) {
            case RESYNC:
            {
                Map<String, ProcessInfo> prevp = processes;
                processes = new HashMap<>();
                prevp.forEach( (k,v) -> {
                    if ( v.getProc() != null ) {
                        processes.put(k,v);
                    }
                });
                processes.forEach( (k,v) -> {
                    if ( v.getProc() != null ) {
                        distribute(RESYNC_RESP, v, null);
                    }
                });
            }
            break;
            case SOFT_SYNC: // no clear
            {
                Map<String, ProcessInfo> prevp = processes;
                processes.forEach( (k,v) -> {
                    if ( v.getProc() != null ) {
                        distribute(RESYNC_RESP, v, null);
                    }
                });
            }
            break;
            case RESYNC_RESP:
            {
                ProcessInfo inf = (ProcessInfo) arg;
                if ( !id.equals(inf.getStarterId()) ) {
                    processes.put(inf.getId(), inf);
                }
            }
            break;
            case TERMINATED:
            {
                ProcessInfo inf = (ProcessInfo) arg;
                processes.remove(inf.getId());
            }
            break;
            case STARTED:
            {
                ProcessInfo inf = (ProcessInfo) arg;
                processes.put(inf.getId(), inf);
            }
            break;
            case SHUTDOWN: {
                System.out.println("shutting down");
                delayed(1000,() -> System.exit(0));
            }
        }
    }

    int counter = 0;
    public void cycle() {
        if ( ! isStopped() ) {
            try {
                counter++;
                if (primaryDesc == null) {
                    initPrimary();
                }
                if (primaryDesc != null) {
                    discoverSiblings();
                    if (counter > 10) {
                        counter = 0;
                    }
                }
                processes = processes.entrySet().stream()
                    .filter(en -> {
                        if (en.getValue().getProc() == null) {
                            if (!siblings.containsKey(en.getValue().getStarterId())) // host died
                            {
                                return false;
                            }
                        } else {
                            if (!en.getValue().getProc().isAlive()) {
                                self().distribute(TERMINATED, en.getValue(), null);
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toMap(en -> en.getKey(), en -> en.getValue()));
            } catch (Throwable th) {
                th.printStackTrace();
            }
//            System.out.println("sibs");
//            siblings.forEach( (k,v) -> System.out.println(v));
            delayed(3_000, () -> cycle());
        }
    }

    public IPromise<Map<String,StarterDesc>> register(StarterDesc other) {
        if ( ! siblings.containsKey(other.getId()) ) {
            siblings.put(other.getId(), other);
            System.out.println( "reg " + other);
        }
        return resolve(siblings);
    }

    private void discoverSiblings() {
        final StarterDesc desc = this.primaryDesc;
        if ( desc == null || desc.getId() == null || desc.getId().equals(id) )
            return;
        if (desc.getRemoteRef().isStopped()) {
            execute( () -> siblings.clear() );
        } else {
            desc.getRemoteRef().register(getDesc()).then((rsib, err) -> {
                if ( rsib != null ) {
                    rsib.put(desc.getId(),desc); // self is missing in receiver
                    siblings.clear();
//                    System.out.println("clear");
                    rsib.forEach( (k,v) -> {
                        if ( ! id.equals(k) ) {
                            siblings.put(k, v);
//                            System.out.println("got "+v);
                        }
                    });
                }
            });
        }
    }

    public void resyncProcesses() {
        distribute(RESYNC,null,null);
    }

    public IPromise<Integer> terminateProcess( String procid, boolean force, int timeoutSec ) {
        ProcessInfo processInfo = processes.get(procid);
        if ( processInfo == null )
            return resolve(null);

        if (processInfo.getProc() == null ) {
            StarterDesc sd = siblings.get(processInfo.getStarterId());
            if (sd!=null)
                return sd.getRemoteRef().terminateProcess(procid,force,timeoutSec);
            else
                return reject("unknown starter:"+processInfo.getStarterId());
        }
        long pid = -1;
        try {
            Field f = processInfo.getProc().getClass().getDeclaredField("pid");
            f.setAccessible(true);
            pid = f.getLong(processInfo.getProc());
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        if ( pid != -1 ) {
            try {
                Process kl = Runtime.getRuntime().exec("kill -9 " + pid);
                kl.waitFor(timeoutSec, TimeUnit.SECONDS);
                self().distribute(TERMINATED, processInfo, null);
                return resolve(new Integer(kl.exitValue()));
            } catch (IOException e) {
                e.printStackTrace();
                return reject(e);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else {
            if ( force )
                processInfo.getProc().destroyForcibly();
            else
                processInfo.getProc().destroy();
        }
        Promise res = new Promise();
        final Process proc = processInfo.getProc();
        exec( () -> {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if ( proc.isAlive() ) {
                res.reject("timeout");
            } else {
                processes.remove(processInfo.getId());
                self().distribute(TERMINATED, processInfo, null);
                res.resolve(proc.exitValue());
            }
            return null;
        });
        return res;
    }

    public IPromise<ProcessInfo> startProcess( String anId, String aName, String workingDir, Map<String,String> env, String ... commandLine ) {
        if ( this.name.equals(aName) )
            aName = null;
        if ( this.id.equals(anId) )
            anId = null;
        if ( aName != null && ! aName.equals(this.name)) {
            if ( anId != null )
                return reject("cannot specify name and id: "+aName+" "+anId);
            final String finalName = aName;
            Optional<StarterDesc> first = siblings.values().stream().filter(desc -> finalName.equals(desc.getName())).findFirst();
            if ( first.isPresent() ) {
                anId = first.get().getId();
            }
        }
        if ( anId != null && ! anId.equals(this.id) ) {
            StarterDesc starterDesc = siblings.get(anId);
            if ( starterDesc != null ) {
                return starterDesc.getRemoteRef().startProcess(anId,aName,workingDir,env,commandLine);
            } else {
                return reject( "no sibling known with id "+anId);
            }
        }
        if ( (anId != null || aName != null) ) {
            return reject("could not find target for "+anId+" "+aName);
        }
        ProcessBuilder pc = new ProcessBuilder(commandLine);
        if ( env != null ) {
            pc.environment().putAll(env);
        }

        pc.directory(new File(workingDir));
        try {
            Process proc = pc.start();
            ProcessInfo pi = new ProcessInfo()
                .cmdLine(commandLine)
                .id(this.id + ":" + pids++)
                .proc(proc)
                .starterName(this.name)
                .starterId(this.id);
            processes.put(pi.getId(), pi);
            self().distribute( STARTED, pi, null);
            return resolve(pi);
        } catch (IOException e) {
            Log.Warn(this, e);
            return reject(e);
        }
    }

    public IPromise<StarterDesc> getInstanceDesc() {
        return resolve(getDesc());
    }

    private StarterDesc getDesc() {
        return new StarterDesc().host(options.getHost()).name(name).id(id).remoteRef(self()).port(options.getPort());
    }

    public IPromise<List<StarterDesc>> getSiblings() {
        List<StarterDesc> collect = siblings.values().stream().collect(Collectors.toList());
        collect.add(getDesc());
        return resolve(collect);
    }

    public IPromise<List<ProcessInfo>> getProcesses() {
        return resolve(processes.entrySet().stream().map( x -> x.getValue() ).collect(Collectors.toList()));
    }

    public static void main(String[] args) throws InterruptedException {

        final StarterArgs options = new StarterArgs();
        new JCommander(options).parse(args);

        ProcessStarter ps = Actors.AsActor(ProcessStarter.class);
        ps.init(options);

        new TCPNIOPublisher()
            .port(options.getPort())
            .facade(ps)
            .publish( act -> {
                System.out.println("Discon "+act);
            });

        // testing
//        ProcessStarter remote = (ProcessStarter) new TCPConnectable(ProcessStarter.class,options.getHost(),options.getPort()).connect( (x,y) -> System.out.println("client disc "+x)).await();
//        ProcessInfo bash = remote.startProcess("/tmp", Collections.emptyMap(), "bash", "-c", "xclock -digital").await();
//
//        List<ProcessInfo> procs = remote.getProcesses().await();
//        procs.forEach( proc -> System.out.println(proc));
//

//        Thread.sleep(3000);
//
//
//        Object await = remote.terminateProcess(bash.getId(), true, 15).await();
//        System.out.println("term result "+await);

        //http://stackoverflow.com/questions/5740390/printing-my-macs-serial-number-in-java-using-unix-commands/5740673#5740673
        //http://stackoverflow.com/questions/1980671/executing-untokenized-command-line-from-java/1980921#1980921
    }

}
