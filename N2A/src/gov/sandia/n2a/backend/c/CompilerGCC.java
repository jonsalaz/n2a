/*
Copyright 2022 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.c;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import gov.sandia.n2a.host.Host;
import gov.sandia.n2a.host.Host.AnyProcess;
import gov.sandia.n2a.host.Windows;

public class CompilerGCC extends Compiler
{
    public static class Factory implements CompilerFactory
    {
        protected Host  host;
        protected Path  gcc;
        protected float version;  // major.minor without any sub-minor version

        public Factory (Host host, Path gcc)
        {
            this.host = host;
            this.gcc  = gcc;

            // Determine version
            try (AnyProcess proc = host.build (gcc.toString (), "--version").start ();
                 BufferedReader reader = new BufferedReader (new InputStreamReader (proc.getInputStream ())))
            {
                // Very fragile, but works with current versions of GCC and Clang.
                String line = reader.readLine ();
                String[] pieces = line.split (" ");
                for (String p : pieces)
                {
                    if (! Character.isDigit (p.charAt (0))) continue;
                    pieces = p.split ("\\.");
                    version = Float.valueOf (pieces[0] + "." + pieces[1]);
                    break;
                }
            }
            catch (Exception e) {e.printStackTrace ();}
        }

        public Compiler make (Path localJobDir)
        {
            return new CompilerGCC (host, localJobDir, gcc);
        }

        public String suffixBinary ()
        {
            return ".bin";
        }

        public String suffixLibraryStatic ()
        {
            return ".a";
        }

        public String suffixLibraryShared ()
        {
            if (host instanceof Windows) return ".dll";
            return ".so";
        }

        public String suffixLibraryWrapper ()
        {
            if (host instanceof Windows) return ".dll.a";
            return "";
        }

        public String prefixLibraryStatic ()
        {
            return "lib";
        }

        public String prefixLibraryShared ()
        {
            if (host instanceof Windows) return "";
            return "lib";
        }

        public boolean wrapperRequired ()
        {
            // This is not strictly true. GCC can link without a static wrapper on Windows.
            // However, the naming of the static link library is more consistent than
            // the naming of the associated DLLs, so this choice makes it easier to find
            // resources like FFmpeg.
            return host instanceof Windows;
        }

        public boolean supportsUnicodeIdentifiers ()
        {
            return version >= 10;
        }
    }

    protected Path         gcc;
    protected List<String> settings = new ArrayList<String> ();
    protected String       optimize = "-O3";

    public CompilerGCC (Host host, Path localJobDir, Path gcc)
    {
        super (host, localJobDir);
        this.gcc = gcc;

        settings.add ("-std=c++11");
        settings.add ("-ffunction-sections");
        settings.add ("-fdata-sections");
    }

    public Path compile () throws Exception
    {
        List<String> command = new ArrayList<String> ();
        command.add (gcc.toString ());
        command.add ("-c");
        if (debug) command.add ("-g");
        else       command.add (optimize);
        if (profiling) command.add ("-pg");
        if (shared) command.add ("-fpic");  // Could use -fPIC, but that option is specifically to avoid size limitations in global offset table that don't apply to any processor we are interested in.
        for (String setting : settings) command.add (setting);

        for (Entry<String,String> define : defines.entrySet ())
        {
            String key   = define.getKey ();
            String value = define.getValue ();
            if (value.isBlank ()) command.add ("-D" + key);
            else                  command.add ("-D" + key + "=" + value);
        }
        for (Path include : includes)
        {
            command.add ("-I" + host.quote (include));
        }
        for (Path source : sources)
        {
            command.add (host.quote (source));
        }

        command.add ("-o");  // goes with next line ...
        command.add (host.quote (output));

        return runCommand (command);
    }

    public Path compileLink () throws Exception
    {
        List<String> command = new ArrayList<String> ();
        command.add (gcc.toString ());
        if (debug) command.add ("-g");
        else       command.add (optimize);
        if (profiling) command.add ("-pg");
        if (shared)
        {
            command.add ("-fpic");
            command.add ("-shared");
        }
        for (String setting : settings) command.add (setting);
        command.add ("-Wl,--gc-sections");

        for (Entry<String,String> define : defines.entrySet ())
        {
            String key   = define.getKey ();
            String value = define.getValue ();
            if (value.isBlank ()) command.add ("-D" + key);
            else                  command.add ("-D" + key + "=" + value);
        }
        for (Path include : includes)
        {
            command.add ("-I" + host.quote (include));
        }
        for (Path source : sources)
        {
            command.add (host.quote (source));
        }
        for (Path object : objects)
        {
            command.add (host.quote (object));
        }
        for (String library : libraries)
        {
            command.add ("-l" + library);
        }
        for (Path libraryDir : libraryDirs)
        {
            command.add ("-L" + host.quote (libraryDir));
        }

        command.add ("-o");
        command.add (host.quote (output));

        return runCommand (command);
    }

    public Path linkLibrary () throws Exception
    {
        List<String> command = new ArrayList<String> ();
        if (shared)
        {
            command.add (gcc.toString ());
            command.add ("-shared");
            if (host instanceof Windows)  // GCC is capable of generating a wrapper library, but does not require one when linking with other code.
            {
                // export-all-symbols -- This is the default unless there is an explicit export in the
                // code. When using JNI on Windows, there are explicit exports, so this is necessary.
                // out-implib -- Output a static wrapper library. This isn't needed by GCC, but makes
                // the output useful for MSVC.
                // TODO: The linker can't handle spaces in the output path, even when quoted.
                // This may be due to how GCC passes the parameters on to LD.
                command.add ("-Wl,--export-all-symbols,--out-implib," + host.quote (output) + ".a");
            }
            for (Path object : objects)
            {
                command.add (host.quote (object));
            }
            for (String library : libraries)
            {
                command.add ("-l" + library);
            }
            for (Path libraryDir : libraryDirs)
            {
                command.add ("-L" + host.quote (libraryDir));
            }
            command.add ("-o");
            command.add (host.quote (output));
        }
        else
        {
            String prefix = gcc.getFileName ().toString ();
            prefix        = prefix.substring (0, prefix.length () - 3);  // strip off "g++" or "gcc"
            Path   parent = gcc.getParent ();
            Path ar;
            if (parent == null) ar = Paths.get (prefix + "ar");       // The usual case, where g++ is specified alone.
            else                ar = parent.resolve (prefix + "ar");  // g++ is prefixed by at least one path element.
            command.add (ar.toString ());
            command.add ("rsc");  // operation=r (insert members, with replacement); modifier=s (create symbol table); modifier=c (expecting to create archive, so don't warn)
            command.add (host.quote (output));
            for (Path object : objects) command.add (host.quote (object));
        }
        return runCommand (command);
    }
}
