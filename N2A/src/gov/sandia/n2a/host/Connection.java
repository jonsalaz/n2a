/*
Copyright 2013-2021 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.host;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.host.Host.AnyProcess;
import gov.sandia.n2a.host.Host.AnyProcessBuilder;

public class Connection implements Closeable
{
    protected MNode           config;
    protected List<Session>   sessions = new ArrayList<Session> ();
    protected Session         session;      // The main session. Final entry in "sessions".
    protected FileSystem      sshfs;
    protected String          hostname;
    protected String          username;
    protected int             timeout;
    protected String          home;         // Path of user's home directory on remote system. Includes leading slash.
    protected boolean         allowDialogs; // Initially false. Dialogs must be specifically enabled by the user.
    protected MessageListener messageListener;

    public static JSch jsch = new JSch ();  // shared between remote execution system and git wrapper
    static
    {
        JSch.setConfig ("max_input_buffer_size", "1048576");  // Memory is cheap these days, so set generous buffer size (1MiB).

        // Load ssh configuration files
        Path homeDir = Paths.get (System.getProperty ("user.home")).toAbsolutePath ();
        Path sshDir = homeDir.resolve (".ssh");  // with dot
        if (! Files.isDirectory (sshDir)) sshDir = homeDir.resolve ("ssh");  // without dot
        try
        {
            if (! Files.isDirectory (sshDir))  // no ssh dir, so create one
            {
                sshDir = homeDir.resolve (".ssh");  // use dot, as this is more universal
                Files.createDirectories (sshDir);
            }

            // Known hosts
            Path known_hosts = sshDir.resolve ("known_hosts");
            if (! Files.exists (known_hosts))  // create empty known_hosts file
            {
                Files.createFile (known_hosts);
            }
            jsch.setKnownHosts (known_hosts.toString ());

            // Identities
            try (DirectoryStream<Path> stream = Files.newDirectoryStream (sshDir))
            {
                for (Path path : stream)
                {
                    if (Files.isDirectory (path)) continue;
                    String name = path.getFileName ().toString ();
                    // TODO: the criteria here are too rigid. It would be better to read the beginning of each file to decide if it is an identity.
                    if (! name.startsWith ("id_")) continue;
                    if (name.contains (".")) continue;  // avoid .pub files
                    jsch.addIdentity (path.toAbsolutePath ().toString ());
                }
            }
        }
        catch (Exception e) {}
    }

    public interface MessageListener
    {
        public void messageReceived ();
    }

    public Connection (MNode config)
    {
        this.config = config;
        hostname = config.getOrDefault (config.key (),                    "address");
        username = config.getOrDefault (System.getProperty ("user.name"), "username");
        home     = config.getOrDefault ("/home/" + username,              "home");
        timeout  = config.getOrDefault (20000,                            "timeout");  // default is 20 seconds
    }

    public synchronized void connect () throws JSchException
    {
        if (isConnected ()) return;
        // Not connected all the way to target host, but there may be relays that are still up.
        // For a given instance of this class, assume that configuration data does not change.
        // Thus, we can try to re-use some portion of the relay path to the target host.
        // If config does change, then we should call close() before connect().
        boolean haveSessions = ! sessions.isEmpty ();  // If not empty, then it must be exactly the right size.

        int last = config.childOrEmpty ("relay").size ();
        session = null;
        String password = config.get ("password");  // may be empty
        for (int i = 0; i <= last; i++)
        {
            ConnectionInfo ci;
            if (haveSessions)
            {
                Session s = sessions.get (i);
                if (s.isConnected ())
                {
                    session = s;
                    continue;
                }
                // Otherwise the session must be created again. Based on googling, it appears illegal to reconnect sessions.
                ci = (ConnectionInfo) s.getUserInfo ();  // But recycle our user info object, because it may contain successful login info.
                ci.triedPassword   = false;
                ci.triedPassphrase = false;
            }
            else
            {
                ci = new ConnectionInfo (this);
            }

            String rhost;
            int    rport;
            String ruser;
            String rpassword;
            if (i == last)  // Use main config
            {
                rhost     = hostname;
                rport     = config.getOrDefault (22, "port");
                ruser     = username;
                rpassword = password;
            }
            else  // Use relay config
            {
                MNode relay = config.child ("relay", i + 1);
                rhost     = relay.get ("address");
                rport     = relay.getOrDefault (22, "port");
                ruser     = relay.getOrDefault (username, "username");
                rpassword = relay.getOrDefault (password, "password");
            }
            if (! haveSessions)
            {
                ci.password = rpassword;
                ci.hostname = rhost;
            }

            if (session == null)
            {
                session = jsch.getSession (ruser, rhost, rport);
            }
            else
            {
                int p = session.setPortForwardingL (0, rhost, rport);
                session = jsch.getSession (ruser, "127.0.0.1", p);
                session.setHostKeyAlias (rhost);
            }
            session.setUserInfo (ci);
            session.setTimeout (timeout); // Applies to all communication, not just initial connection.
            session.setConfig ("StrictHostKeyChecking", "no");  // Don't prompt user; just accept the host key and add to known_hosts file.
            session.connect ();           // Uses timeout set above.
            if (haveSessions) sessions.set (i, session);
            else              sessions.add (session);
        }
    }

    public synchronized void close ()
    {
        for (int i = sessions.size () - 1; i >= 0; i--)
        {
            Session s = sessions.get (i);
            if (s.isConnected ()) s.disconnect ();
        }
        sessions.clear ();
        session = null;
    }

    public synchronized boolean isConnected ()
    {
        return  session != null  &&  session.isConnected ();
    }

    /**
        Used by ConnectionInfo to notify us that a new message has been received.
    **/
    protected void messageReceived ()
    {
        if (messageListener != null) messageListener.messageReceived ();
    }

    /**
        Constructs a compendium of unique messages from all the sessions.
    **/
    public String getMessages ()
    {
        if (sessions.isEmpty ()) return "";

        StringBuilder result = new StringBuilder ();
        boolean firstSession = true;
        for (Session s : sessions)
        {
            if (! firstSession) result.append ("\n" + s.getHost () + "\n");
            firstSession = false;

            ConnectionInfo ci = (ConnectionInfo) s.getUserInfo ();
            boolean firstMessage = true;
            for (String m : ci.messages)
            {
                if (! firstMessage) result.append ("\n------------------------\n");
                firstMessage = false;
                result.append (m);
            }
        }
        return result.toString ();
    }

    /**
        @return A file system bound to the remote host. Default directory for relative paths
        is the user's home. Absolute paths are with respect to the usual root directory.
        @throws JSchException
    **/
    public synchronized FileSystem getFileSystem () throws Exception
    {
        connect ();
        if (sshfs == null)
        {
            Map<String,Object> env = new HashMap<String,Object> ();
            env.put ("connection", this);
            URI uri = new URI ("ssh://" + hostname + home);
            try
            {
                sshfs = FileSystems.newFileSystem (uri, env);
            }
            catch (FileSystemAlreadyExistsException e)
            {
                // It is possible for two host to share the exact same filesystem.
                // The host could be an alias with an alternate configuration.
                sshfs = FileSystems.getFileSystem (uri);
            }
        }
        return sshfs;
    }

    public RemoteProcessBuilder build (String... command)
    {
        return new RemoteProcessBuilder (command);
    }

    public RemoteProcessBuilder build (List<String> command)
    {
        return new RemoteProcessBuilder (command.toArray (new String[command.size ()]));
    }

    public class RemoteProcessBuilder implements AnyProcessBuilder
    {
        protected String             command;
        protected Path               fileIn;
        protected Path               fileOut;
        protected Path               fileErr;
        protected Map<String,String> environment;

        public RemoteProcessBuilder (String... command)
        {
            String combined = "";
            if (command.length > 0) combined = command[0];
            for (int i = 1; i < command.length; i++) combined += " " + command[i];
            this.command = combined;
        }

        public RemoteProcessBuilder redirectInput (Path file)
        {
            fileIn = file;
            return this;
        }

        public RemoteProcessBuilder redirectOutput (Path file)
        {
            fileOut = file;
            return this;
        }

        public RemoteProcessBuilder redirectError (Path file)
        {
            fileErr = file;
            return this;
        }

        public Map<String,String> environment ()
        {
            if (environment == null) environment = new HashMap<String,String> ();
            return environment;
        }

        public RemoteProcess start () throws IOException, JSchException
        {
            RemoteProcess process = new RemoteProcess (command);

            // Streams must be configured before connect.
            // A redirected stream is of the opposite type from what we would read directly.
            // IE: stdout (from the perspective of the remote process) must feed into something
            // on our side. We either read it directly, in which case it is an input stream,
            // or we redirect it to file, in which case it is an output stream.
            // Can this get any more confusing?
            // One thing that makes it confusing is that the JSch does not pair get/set methods.
            // For example, getOutputStream() and setOutputStream() do not actually connect the same stream.
            // Instead, getOutputStream() connects stdout, while setOutputStream() connects stdin.
            if (fileIn == null)  process.stdin = process.channel.getOutputStream ();
            else                 process.channel.setInputStream (Files.newInputStream (fileIn));
            if (fileOut == null) process.stdout = process.channel.getInputStream ();
            else                 process.channel.setOutputStream (Files.newOutputStream (fileOut));
            if (fileErr == null) process.stderr = process.channel.getErrStream ();
            else                 process.channel.setErrStream (Files.newOutputStream (fileErr));

            if (environment != null)
            {
                for (Entry<String,String> e : environment.entrySet ())
                {
                    process.channel.setEnv (e.getKey (), e.getValue ());
                }
            }

            process.channel.connect ();  // This actually starts the remote process.
            return process;
        }
    }

    /**
        Drop-in equivalent to Process that works for a remote process executed via ssh.
        The one difference is that this should be created within a try-with-resources so that
        it will be automatically closed and release the ssh channel.
    **/
    public class RemoteProcess extends Process implements AnyProcess
    {
        protected ChannelExec channel;

        // The following streams are named from the perspective of the remote process.
        // IE: the stdin of the remote process will receive input from us.
        protected OutputStream stdin;  // From our perspective, transmitting data, this needs to be an output stream.
        protected InputStream  stdout;
        protected InputStream  stderr;

        public RemoteProcess (String command) throws JSchException
        {
            connect ();
            channel = (ChannelExec) session.openChannel ("exec");  // Best I can tell, openChannel() is thread-safe.
            channel.setCommand (command);
        }

        public void close ()
        {
            channel.disconnect ();  // OK to call disconnect() multiple times
        }

        public OutputStream getOutputStream ()
        {
            if (stdin == null) stdin = new NullOutputStream ();
            return stdin;
        }

        public InputStream getInputStream ()
        {
            if (stdout == null) stdout = new NullInputStream ();
            return stdout;
        }

        public InputStream getErrorStream ()
        {
            if (stderr == null) stderr = new NullInputStream ();
            return stderr;
        }

        public int waitFor () throws InterruptedException
        {
            while (! channel.isClosed ()) Thread.sleep (1000);
            return channel.getExitStatus ();
        }

        public int exitValue () throws IllegalThreadStateException
        {
            if (! channel.isClosed ()) throw new IllegalThreadStateException ();
            return channel.getExitStatus ();
        }

        public void destroy ()
        {
            try {channel.sendSignal ("TERM");}
            catch (Exception e) {}
        }

        public RemoteProcess destroyForcibly ()
        {
            try {channel.sendSignal ("KILL");}
            catch (Exception e) {}
            return this;
        }

        public boolean isAlive ()
        {
            return ! channel.isClosed ();
        }
    }

    // The following null streams were copied from ProcessBuilder.
    // Unfortunately, they are private to that class, so can't be used here.

    public static class NullInputStream extends InputStream
    {
        public int read ()
        {
            return -1;
        }

        public int available ()
        {
            return 0;
        }
    }

    public static class NullOutputStream extends OutputStream
    {
        public void write (int b) throws IOException
        {
            throw new IOException ("Stream closed");
        }
    }
}
