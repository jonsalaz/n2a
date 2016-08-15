/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.umf.platform.execenvs;

import gov.sandia.umf.platform.runs.Run;
import gov.sandia.umf.platform.runs.RunEnsemble;
import gov.sandia.umf.platform.runs.RunState;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import replete.process.ProcessUtil;
import replete.util.FileUtil;

public class Linux extends LocalMachineEnv
{
    private static Logger logger = Logger.getLogger(Linux.class);

    @Override
    public Set<Integer> getActiveProcs () throws Exception
    {
        Set<Integer> result = new TreeSet<Integer> ();
        String[] cmdArray = new String[] {"ps", "-eo", "pid,command"};
        String[] lines = ProcessUtil.getOutput (cmdArray);
        for (String line : lines)
        {
            line = line.toLowerCase ();
            if (line.contains ("n2a_job"))
            {
                line = line.trim ();
                String[] parts = line.split ("\\s+");  // any amount/type of whitespace forms the delimiter
                result.add (new Integer (parts[0]));
            }
        }
        return result;
    }

    @Override
    public long getProcMem(Integer procNum)
    {
        long result = -1;    // error condition
        String[] cmdArray = new String[] {"ps", "-p", procNum.toString(), "-o", "pid,rss"};
        String[] lines = ProcessUtil.getOutput (cmdArray);
        if (lines.length == 2) {   // first line is headers, second has info we want
            String line = lines[1].trim ();
            String[] parts = line.split ("\\s+");  // any amount/type of whitespace forms the delimiter
            result = Long.parseLong(parts[1]) * 1024;
        }
        return result;
    }

    @Override
    public void submitJob (RunState run) throws Exception
    {
        String command = run.getNamedValue ("command");
        String jobDir  = run.getNamedValue ("jobDir");
        File out = new File (jobDir, "out");
        File err = new File (jobDir, "err");

        File script = new File (jobDir, "n2a_job");
        FileUtil.writeTextContent (script, "#!/bin/bash\n" + command + " > '" + out.getAbsolutePath () + "' 2> '" + err.getAbsolutePath () + "' &\n");

        String [] commandParm = {"chmod", "u+x", script.getAbsolutePath ()};
        Process p = Runtime.getRuntime ().exec (commandParm);
        p.waitFor ();
        if (p.exitValue () != 0) throw new Exception ("Failed to change permissions on job script:\n" + FileUtil.getTextContent (p.getErrorStream ()));

        commandParm = new String[] {script.getAbsolutePath ()};
        p = Runtime.getRuntime ().exec (commandParm);
        p.waitFor ();
        if (p.exitValue () != 0) throw new Exception ("Failed to run job:\n" + FileUtil.getTextContent (p.getErrorStream ()));
    }

    @Override
    public String getNamedValue (String name, String defaultValue)
    {
        if (name.equalsIgnoreCase ("name")) return "Linux";
        if (name.equalsIgnoreCase ("xyce.binary"))
        {
            return "Xyce";
        }
        return super.getNamedValue (name, defaultValue);
    }
}
