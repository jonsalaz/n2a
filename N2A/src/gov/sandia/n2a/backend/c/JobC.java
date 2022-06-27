/*
Copyright 2013-2022 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.c;

import gov.sandia.n2a.backend.internal.InternalBackendData.EventSource;
import gov.sandia.n2a.backend.internal.InternalBackendData.EventTarget;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.eqset.EquationEntry;
import gov.sandia.n2a.eqset.EquationSet;
import gov.sandia.n2a.eqset.EquationSet.Conversion;
import gov.sandia.n2a.host.Host;
import gov.sandia.n2a.eqset.EquationSet.ConnectionBinding;
import gov.sandia.n2a.eqset.EquationSet.ConnectionMatrix;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.eqset.VariableReference;
import gov.sandia.n2a.language.AccessVariable;
import gov.sandia.n2a.language.AccessElement;
import gov.sandia.n2a.language.BuildMatrix;
import gov.sandia.n2a.language.Constant;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Split;
import gov.sandia.n2a.language.Transformer;
import gov.sandia.n2a.language.Type;
import gov.sandia.n2a.language.Visitor;
import gov.sandia.n2a.language.function.Delay;
import gov.sandia.n2a.language.function.Event;
import gov.sandia.n2a.language.function.Input;
import gov.sandia.n2a.language.function.Mfile;
import gov.sandia.n2a.language.function.Output;
import gov.sandia.n2a.language.function.ReadMatrix;
import gov.sandia.n2a.language.operator.Add;
import gov.sandia.n2a.language.type.Matrix;
import gov.sandia.n2a.language.type.Scalar;
import gov.sandia.n2a.language.type.Text;
import gov.sandia.n2a.plugins.ExtensionPoint;
import gov.sandia.n2a.plugins.PluginManager;
import gov.sandia.n2a.plugins.extpoints.Backend;
import gov.sandia.n2a.plugins.extpoints.Backend.AbortRun;
import gov.sandia.n2a.ui.eq.tree.NodePart;
import gov.sandia.n2a.ui.jobs.NodeJob;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public class JobC extends Thread
{
    protected static Set<Host>            runtimeBuilt    = new HashSet<Host> ();            // collection of Hosts for which runtime has already been checked/built during this session.
    protected static Map<Host,List<Path>> providedObjects = new HashMap<Host,List<Path>> (); // object code or archives provided by extensions. The string is suitable for constructing compiler command line.

    public    MNode       job;
    protected EquationSet digestedModel;

    public    Host env;
    public    Path localJobDir;
    protected Path jobDir;     // local or remote
    public    Path runtimeDir; // local or remote
    public    Path gcc;        // local or remote

    protected boolean supportsUnicodeIdentifiers;
    public    String  T;
    protected String  SIMULATOR;
    protected long    seed;
    protected boolean during;
    protected boolean after;
    protected boolean kokkos;  // profiling method
    public    boolean gprof;   // profiling method
    public    boolean debug;   // compile with debug symbols; applies to current model as well as any runtime components that happen to get rebuilt
    protected boolean cli;     // command-line interface
    protected boolean lib;     // library mode, suitable for Python wrapper or other external integration
    protected boolean shared;  // make shared rather than static library; only meaningful when lib is true
    public    boolean tls;     // Make global objects thread-local, so multiple simulations can be run in same process. (Generally, it is cleaner to use separate process for each simulation, but some users want this.)
    protected boolean IOvectorWritten;  // Indicates that the abstract class "IOvector" has been inserted already.
    protected List<ProvideOperator> extensions = new ArrayList<ProvideOperator> ();
    
    // These values are unique across the whole simulation, so they go here rather than BackendDataC.
    // Where possible, the key is a String. Otherwise, it is an Operator which is specific to one expression.
    protected HashMap<Object,String> matrixNames    = new HashMap<Object,String> ();
    protected HashMap<Object,String> mfileNames     = new HashMap<Object,String> ();
    protected HashMap<Object,String> inputNames     = new HashMap<Object,String> ();
    protected HashMap<Object,String> outputNames    = new HashMap<Object,String> ();
    public    HashMap<Object,String> stringNames    = new HashMap<Object,String> ();
    public    HashMap<Object,String> extensionNames = new HashMap<Object,String> ();  // Shared by all extension-provided operators.

    // Work around the initialization sequencing problem by delaying the call to holderHelper until main().
    // To do this, we need to stash variable names. This may seem redundant with the above maps,
    // but this is a more limited case.
    protected List<Constant>   staticMatrix  = new ArrayList<Constant> ();  // constant matrices that should be statically initialized
    protected List<ReadMatrix> mainMatrix    = new ArrayList<ReadMatrix> ();
    protected List<Mfile>      mainMfile     = new ArrayList<Mfile> ();
    protected List<Input>      mainInput     = new ArrayList<Input> ();
    protected List<Output>     mainOutput    = new ArrayList<Output> ();
    public    List<Operator>   mainExtension = new ArrayList<Operator> ();  // Shared by all extension-provided operators.

    public JobC (MNode job)
    {
        super ("C Job");
        this.job = job;

        // Collect plugin renderers
        List<ExtensionPoint> exps = PluginManager.getExtensionsForPoint (ProvideOperator.class);
        for (ExtensionPoint exp : exps) extensions.add ((ProvideOperator) exp);
    }

    public void run ()
    {
        localJobDir = Host.getJobDir (Host.getLocalResourceDir (), job);
        Path errPath = localJobDir.resolve ("err");
        try {Backend.err.set (new PrintStream (new FileOutputStream (errPath.toFile (), true), false, "UTF-8"));}
        catch (Exception e) {}

        try
        {
            Files.createFile (localJobDir.resolve ("started"));
            MNode model = NodeJob.getModel (job);

            T = model.getOrDefault ("float", "$metadata", "backend", "c", "type");
            if (T.startsWith ("int")  &&  T.length () > 3)
            {
                T = "int";
                Backend.err.get ().println ("WARNING: Only supported integer type is 'int', which is assumed to be signed 32-bit.");
            }
            if (! T.equals ("int")  &&  ! T.equals ("double")  &&  ! T.equals ("float"))
            {
                T = "float";
                Backend.err.get ().println ("WARNING: Unsupported numeric type. Defaulting to single-precision float.");
            }

            kokkos = model.getFlag ("$metadata", "backend", "c", "kokkos");
            gprof  = model.getFlag ("$metadata", "backend", "c", "gprof");
            debug  = model.getFlag ("$metadata", "backend", "c", "debug");
            cli    = model.getFlag ("$metadata", "backend", "c", "cli");
            tls    = model.getFlag ("$metadata", "backend", "c", "tls");

            String e = model.get ("$metadata", "backend", "all", "event");
            switch (e)
            {
                case "before":
                    during = false;
                    after  = false;
                    break;
                case "after":
                    during = false;
                    after  = true;
                default:  // during
                    during = true;
                    after  = false;
            }

            env              = Host.get (job);
            Path resourceDir = env.getResourceDir ();
            jobDir           = Host.getJobDir (resourceDir, job);  // Unlike localJobDir (which is created by MDir), this may not exist until we explicitly create it.
            gcc              = resourceDir.getFileSystem ().getPath (env.config.getOrDefault ("g++", "backend", "c", "cxx"));  // No good way to decide absolute path for the default value. Maybe need to call "which" command for this.
            runtimeDir       = resourceDir.resolve ("backend").resolve ("c");
            rebuildRuntime ();

            Files.createDirectories (jobDir);  // digestModel() might write to a remote file (params), so we need to ensure the dir exists first.
            digestedModel = new EquationSet (model);
            digestModel ();
            String duration = digestedModel.metadata.get ("duration");
            if (! duration.isEmpty ()) job.set (duration, "duration");

            seed = -1;
            if (digestedModel.usesRandom ())  // only record seed if actually used
            {
                seed = model.getOrDefault (System.currentTimeMillis () & 0x7FFFFFFF, "$metadata", "seed");
                job.set (seed, "seed");
            }

            System.out.println (digestedModel.dump (false));

            Path source = jobDir.resolve ("model.cc");
            generateCode (source);

            if (lib)
            {
                makeLibrary (source);
                job.clear ("backendStatus");
            }
            else
            {
                String command = env.quote (build (source));

                // The C program could append to the same error file, so we need to close the file before submitting.
                PrintStream ps = Backend.err.get ();
                if (ps != System.err)
                {
                    ps.close ();
                    Backend.err.remove ();
                    job.set (Host.size (errPath), "errSize");
                }

                job.clear ("backendStatus");
                env.submitJob (job, env.clobbersOut (), command);
            }
        }
        catch (Exception e)
        {
            PrintStream ps = Backend.err.get ();
            if (ps == System.err)  // Need to reopen err stream.
            {
                try {Backend.err.set (ps = new PrintStream (new FileOutputStream (errPath.toFile (), true), false, "UTF-8"));}
                catch (Exception e2) {}
            }
            if (e instanceof AbortRun)
            {
                String message = e.getMessage ();
                if (message != null) ps.println (message);
            }
            else e.printStackTrace (ps);

            try {Host.stringToFile ("failure", localJobDir.resolve ("finished"));}
            catch (Exception f) {}
        }

        // If an exception occurred, the err file could still be open.
        PrintStream ps = Backend.err.get ();
        if (ps != System.err) ps.close ();
    }

    public void rebuildRuntime () throws Exception
    {
        // Update runtime source files, if necessary
        boolean changed = false;
        if (env.config.getFlag ("backend", "c", "compilerChanged"))
        {
            changed = true;
            runtimeBuilt.remove (env);
            providedObjects.remove (env);
        }
        if (! runtimeBuilt.contains (env))
        {
            if (unpackRuntime ()) changed = true;
            for (ProvideOperator pf : extensions)
            {
                Path po = pf.rebuildRuntime (this);
                if (po == null) continue;
                List<Path> envProvidedObjects = providedObjects.get (env);
                if (envProvidedObjects == null)
                {
                    envProvidedObjects = new ArrayList<Path> ();
                    providedObjects.put (env, envProvidedObjects);
                }
                envProvidedObjects.add (po);
            }
            runtimeBuilt.add (env);  // Stop checking files for this session.
        }
        env.config.clear ("backend", "c", "compilerChanged");

        if (changed)  // Delete existing object files
        {
            try (DirectoryStream<Path> list = Files.newDirectoryStream (runtimeDir))
            {
                for (Path file : list)
                {
                    if (file.getFileName ().toString ().endsWith (".o"))
                    {
                        job.set ("", "backendStatus");
                        Files.delete (file);
                    }
                }
            }
            catch (IOException e) {}
        }

        // Compile runtime
        Compiler.Factory factory = BackendC.getFactory (env);
        supportsUnicodeIdentifiers = factory.supportsUnicodeIdentifiers ();
        Map<String,Boolean> sources = new HashMap<String,Boolean> ();  // List of source names, associated with flag indicating that a type-specific object should be built.
        sources.put ("runtime",   true);
        sources.put ("holder",    true);
        sources.put ("MNode",     true);  // Doesn't really have any template on numeric type, but other variants are meaningful.
        sources.put ("profiling", false);
        if (T.equals ("int")) sources.put ("fixedpoint", true);
        for (String stem : sources.keySet ())
        {
            boolean typeSpecific = sources.get (stem);
            String objectName = typeSpecific ? objectName (stem) : stem + ".o";

            Path object = runtimeDir.resolve (objectName);
            if (Files.exists (object)) continue;
            job.set ("Compiling " + objectName, "backendStatus");

            Compiler c = factory.make (localJobDir);
            if (gprof) c.setProfiling ();
            if (debug) c.setDebug ();
            c.addInclude (runtimeDir);
            c.addDefine ("n2a_T", T);
            if (T.equals ("int")) c.addDefine ("n2a_FP");
            if (tls) c.addDefine ("n2a_TLS");
            c.addSource (runtimeDir.resolve (stem + ".cc"));
            c.setOutput (object);

            Path out = c.compile ();
            Files.delete (out);
        }
    }

    /**
        Places resources specific to this backend into runtimeDir.
        runtimeDir must be set before calling this function.
    **/
    public boolean unpackRuntime () throws Exception
    {
        job.set ("Unpacking runtime", "backendStatus");

        return unpackRuntime
        (
            JobC.class, job, runtimeDir, "runtime/",
            "fixedpoint.cc", "fixedpoint.h", "fixedpoint.tcc",
            "holder.cc", "holder.h", "holder.tcc",
            "KDTree.h", "StringLite.h",
            "matrix.h", "Matrix.tcc", "MatrixFixed.tcc", "MatrixSparse.tcc", "pointer.h",
            "MNode.h", "MNode.cc",
            "nosys.h",
            "runtime.cc", "runtime.h", "runtime.tcc",
            "profiling.h", "profiling.cc",
            "OutputParser.h"  // Not needed by runtime, but provided as a utility for users.
        );
    }

    public static boolean unpackRuntime (Class<?> from, MNode job, Path runtimeDir, String prefix, String... names) throws Exception
    {
        boolean changed = false;
        Files.createDirectories (runtimeDir);
        for (String s : names)
        {
            if (job != null) job.set ("Unpacking " + s, "backendStatus");

            URL url = from.getResource (prefix + s);
            long resourceModified = url.openConnection ().getLastModified ();
            Path f = runtimeDir.resolve (s);
            long fileModified = Host.lastModified (f);
            if (resourceModified > fileModified)
            {
                changed = true;
                Files.copy (url.openStream (), f, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return changed;
    }

    /**
        Runtime object code files will be named source_type_featureA_featureB...
        in a standard order established by this function. 
    **/
    public String objectName (String stem)
    {
        StringBuilder result = new StringBuilder ();
        result.append (stem);
        result.append ("_" + T);
        if (gprof) result.append ("_gprof");
        if (debug) result.append ("_debug");
        if (tls  ) result.append ("_tls");
        result.append (".o");
        return result.toString ();
    }

    public Path build (Path source) throws Exception
    {
        job.set ("Compiling model", "backendStatus");

        Compiler.Factory factory = BackendC.getFactory (env);
        String name   = source.getFileName ().toString ();
        int    pos    = name.lastIndexOf ('.');
        String stem   = pos > 0 ? name.substring (0, pos) : name;
        Path   binary = source.getParent ().resolve (stem + factory.suffixBinary ());

        Compiler c = factory.make (localJobDir);
        if (gprof) c.setProfiling ();
        if (debug) c.setDebug ();
        c.addInclude (runtimeDir);
        for (ProvideOperator po : extensions)
        {
            Path include = po.include (this);
            if (include == null) continue;
            c.addInclude (include.getParent ());
        }
        c.addDefine ("n2a_T", T);
        if (T.equals ("int")) c.addDefine ("n2a_FP");
        if (tls) c.addDefine ("n2a_TLS");
        c.setOutput (binary);
        c.addSource (source);
        c.addObject (runtimeDir.resolve (objectName ("runtime")));
        c.addObject (runtimeDir.resolve (objectName ("holder")));
        c.addObject (runtimeDir.resolve (objectName ("MNode")));
        if (T.equals ("int")) c.addObject (runtimeDir.resolve (objectName ("fixedpoint")));
        List<Path> envProvidedObjects = providedObjects.get (env);
        if (envProvidedObjects != null) for (Path po : envProvidedObjects) c.addObject (po);
        if (kokkos)
        {
            c.addObject (runtimeDir.resolve ("profiling.o"));
            c.addLibrary (Paths.get ("dl"));  // TODO: should this be made on the host file system?
        }

        Path out = c.compileLink ();
        Files.delete (out);

        return binary;
    }

    public void makeLibrary (Path source) throws Exception
    {
        // In order to make a library, we must compile in two steps:
        // first generate an object file, then link as library.
        Compiler.Factory factory = BackendC.getFactory (env);
        String name    = source.getFileName ().toString ();
        int    pos     = name.lastIndexOf ('.');
        String stem    = pos > 0 ? name.substring (0, pos) : name;
        Path   parent  = source.getParent ();
        Path   object  = parent.resolve (stem + ".o");
        Path   library = parent.resolve (stem + factory.suffixLibraryStatic ());

        // 1) Generate object file
        Compiler c = factory.make (localJobDir);
        if (gprof) c.setProfiling ();
        if (debug) c.setDebug ();
        c.addInclude (runtimeDir);
        for (ProvideOperator po : extensions)
        {
            Path include = po.include (this);
            if (include == null) continue;
            c.addInclude (include.getParent ());
        }
        c.addDefine ("n2a_T", T);
        if (T.equals ("int")) c.addDefine ("n2a_FP");
        if (tls) c.addDefine ("n2a_TLS");
        c.setOutput (object);
        c.addSource (source);
        Path out = c.compile ();
        Files.delete (out);

        // 2) Link library
        c.setOutput (library);
        c.addObject (object);
        c.addObject (runtimeDir.resolve (objectName ("runtime")));
        c.addObject (runtimeDir.resolve (objectName ("holder")));
        c.addObject (runtimeDir.resolve (objectName ("MNode")));
        if (T.equals ("int")) c.addObject (runtimeDir.resolve (objectName ("fixedpoint")));
        List<Path> envProvidedObjects = providedObjects.get (env);
        if (envProvidedObjects != null) for (Path po : envProvidedObjects) c.addObject (po);
        if (kokkos)
        {
            c.addObject (runtimeDir.resolve ("profiling.o"));
            c.addLibrary (Paths.get ("dl"));
        }
        out = c.linkLibrary (false);
        Files.delete (out);
    }

    public void digestModel () throws Exception
    {
        job.set ("Analyzing model", "backendStatus");

        if (digestedModel.source.containsKey ("pin"))
        {
            digestedModel.collectPins ();
            digestedModel.fillAutoPins ();
            digestedModel.resolvePins ();
            digestedModel.purgePins ();
        }
        digestedModel.resolveConnectionBindings ();
        digestedModel.addGlobalConstants ();
        digestedModel.addSpecials ();  // $connect, $index, $init, $n, $t, $t', $type
        digestedModel.addAttribute ("global",      false, true,  "$max", "$min", "$k", "$radius");
        digestedModel.addAttribute ("global",      false, false, "$n");
        digestedModel.addAttribute ("preexistent", true,  false, "$index", "$t'", "$t");  // Technically, $index is not pre-existent, but always receives special handling which has the same effect.
        if (cli)
        {
            try (BufferedWriter params = Files.newBufferedWriter (jobDir.resolve ("params")))
            {
                tagCommandLineParameters (digestedModel, params);
            }
            catch (Exception e) {e.printStackTrace ();}
        }
        digestedModel.resolveLHS ();
        digestedModel.fillIntegratedVariables ();
        digestedModel.findIntegrated ();
        digestedModel.resolveRHS ();
        digestedModel.flatten ("c");
        digestedModel.findExternal ();
        digestedModel.sortParts ();
        digestedModel.checkUnits ();
        digestedModel.findConstants ();
        digestedModel.determineTraceVariableName ();
        digestedModel.collectSplits ();
        digestedModel.findDeath ();
        addImplicitDependencies (digestedModel);
        digestedModel.removeUnused ();  // especially get rid of unneeded $variables created by addSpecials()
        createBackendData (digestedModel);
        findPathToContainer (digestedModel);
        digestedModel.findAccountableConnections ();
        digestedModel.findTemporary ();  // for connections, makes $p and $project "temporary" under some circumstances. TODO: make sure this doesn't violate evaluation order rules
        digestedModel.determineOrder ();
        digestedModel.findDerivative ();
        digestedModel.findInitOnly ();  // propagate initOnly through ASTs
        digestedModel.purgeInitOnlyTemporary ();
        digestedModel.setAttributesLive ();
        digestedModel.forceTemporaryStorageForSpecials ();
        findLiveReferences (digestedModel);
        digestedModel.determineTypes ();
        digestedModel.determineDuration ();
        digestedModel.assignParents ();
        if (T.equals ("int")) digestedModel.determineExponents ();
        digestedModel.findConnectionMatrix ();
        analyzeEvents (digestedModel);
        analyze (digestedModel);
    }

    public void tagCommandLineParameters (EquationSet s, Writer params) throws IOException
    {
        for (Variable v : s.variables)
        {
            // Must be a simple constant tagged "param"
            if (v.metadata == null) continue;  // This can happen for $variables that are added at compile time.
            MNode nodeCLI = v.metadata.child ("backend", "c", "cli");
            if (nodeCLI == null)  // Without CLI flag, base decision on param flag.
            {
                if (! v.metadata.getFlag ("param")) continue;
            }
            else  // CLI flag takes precedence over everything else.
            {
                if (! nodeCLI.getFlag ()) continue;
            }
            if (v.equations.size () != 1) continue;
            EquationEntry e = v.equations.first ();
            if (e.condition != null) continue;
            if (! (e.expression instanceof Constant)) continue;
            v.addAttribute ("initOnly");  // prevents v from being eliminated by simplify
            v.addAttribute ("cli");  // private tag to remind us to generate CLI code for this variable

            String defaultValue = s.source.get (v.nameString ());

            // Determine parameter format/range hint
            String hint = v.metadata.get ("param");
            if (hint.isEmpty ()) hint = v.metadata.get ("study");

            params.append (v.fullName () + "=" + defaultValue);
            if (! hint.isEmpty ()) params.append (";" + hint);
            params.append ("\n");
        }

        for (EquationSet p : s.parts) tagCommandLineParameters (p, params);
    }

    /**
        Depends on the results of: addAttributes(), findDeath()
    **/
    public void addImplicitDependencies (EquationSet s)
    {
        if (T.equals ("int"))
        {
            // Force top-level model to keep $t', so it can retrieve time exponent.
            Variable dt = s.find (new Variable ("$t", 1));
            dt.addUser (s);
        }
        if (s.isSingleton ())
        {
            Variable live = s.find (new Variable ("$live"));
            live.addUser (s);
        }
        addImplicitDependenciesRecursive (s);
    }

    public void addImplicitDependenciesRecursive (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            addImplicitDependenciesRecursive (p);
        }
    
        final Variable dt = s.find (new Variable ("$t", 1));

        if (s.lethalP)
        {
            Variable p = s.find (new Variable ("$p"));  // Which should for sure exist, since lethalP implies it.
            p.addDependencyOn (dt);
        }

        class VisitorDt implements Visitor
        {
            public Variable from;
            public boolean visit (Operator op)
            {
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    if (i.usesTime ()  &&  ! from.hasAttribute ("global")  &&  ! T.equals ("int"))
                    {
                        from.addDependencyOn (dt);  // So that time epsilon can be determined from dt when initializing input.
                    }
                }
                if (op instanceof Event)
                {
                    Event e = (Event) op;
                    if (e.operands.length > 1  &&  e.operands[1].getDouble () > 0)  // constant delay > 0
                    {
                        // We depend on $t' to know time exponent.
                        // This is necessary regardless of whether T=="int", because eventGenerate() handles this in a generic way.
                        EquationSet root = s.getRoot ();
                        Variable rootDt = root.find (dt);
                        rootDt.addUser (root);
                    }
                }
                return true;
            }
        }
        VisitorDt visitor = new VisitorDt ();
    
        for (Variable v : s.variables)
        {
            visitor.from = v;
            v.visit (visitor);
            if (v.derivative != null) v.addDependencyOn (dt);

            if (lib  &&  v.getMetadata ().getFlag ("backend", "c", "vector"))
            {
                EquationSet p = s;
                while (p != null)
                {
                    p.needInstanceTracking = true;  // So it's possible to specify the exact population for the IO vector.
                    p = p.container;
                }
            }
        }

        // needInstanceTracking implies need $index
        if (s.needInstanceTracking  &&  ! s.isSingleton ())
        {
            Variable index = s.find (new Variable ("$index"));
            index.addUser (s);
        }
    }

    public void createBackendData (EquationSet s)
    {
        if (! (s.backendData instanceof BackendDataC)) s.backendData = new BackendDataC ();
        for (EquationSet p : s.parts) createBackendData (p);
    }

    public void findPathToContainer (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            findPathToContainer (p);
        }

        if (s.connectionBindings != null)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                if (c.endpoint.container == s.container)
                {
                    BackendDataC bed = (BackendDataC) s.backendData;
                    bed.pathToContainer = c.alias;
                    break;
                }
            }
        }
    }

    public void findLiveReferences (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            findLiveReferences (p);
        }

        if (s.lethalConnection  ||  s.lethalContainer)
        {
            ArrayList<Object>         resolution     = new ArrayList<Object> ();
            NavigableSet<EquationSet> touched        = new TreeSet<EquationSet> ();
            if (! (s.backendData instanceof BackendDataC)) s.backendData = new BackendDataC ();
            findLiveReferences (s, resolution, touched, ((BackendDataC) s.backendData).localReference, false);
        }
    }

    @SuppressWarnings("unchecked")
    public void findLiveReferences (EquationSet s, ArrayList<Object> resolution, NavigableSet<EquationSet> touched, List<VariableReference> localReference, boolean terminate)
    {
        if (terminate)
        {
            Variable live = s.find (new Variable ("$live"));
            if (live == null  ||  live.hasAttribute ("constant")) return;
            if (live.hasAttribute ("initOnly"))
            {
                if (touched.add (s))
                {
                    VariableReference result = new VariableReference ();
                    result.variable = live;
                    result.resolution = (ArrayList<Object>) resolution.clone ();
                    localReference.add (result);
                    s.referenced = true;
                }
                return;
            }
            // The third possibility is "accessor", in which case we fall through ...
        }

        // Recurse up to container
        if (s.lethalContainer)
        {
            resolution.add (s.container);
            findLiveReferences (s.container, resolution, touched, localReference, true);
            resolution.remove (resolution.size () - 1);
        }

        // Recurse into connections
        if (s.lethalConnection)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                resolution.add (c);
                findLiveReferences (c.endpoint, resolution, touched, localReference, true);
                resolution.remove (resolution.size () - 1);
            }
        }
    }

    public void analyzeEvents (EquationSet s) throws Backend.AbortRun
    {
        BackendDataC bed = (BackendDataC) s.backendData;
        bed.analyzeEvents (s);
        for (EquationSet p : s.parts) analyzeEvents (p);
    }

    public void analyze (EquationSet s)
    {
        for (EquationSet p : s.parts) analyze (p);
        BackendDataC bed = (BackendDataC) s.backendData;
        bed.analyze (s);
        bed.analyzeLastT (s);

        // Special case for host that clobber stdout
        // Look for output("", ...) and replace first parameter with "out"
        if (! env.clobbersOut ()) return;
        Transformer t = new Transformer ()
        {
            public Operator transform (Operator op)
            {
                if (op instanceof Output)
                {
                    Output out = (Output) op;
                    if (out.operands[0].toString ().isBlank ()) out.operands[0] = new Constant ("out");
                    return out;  // Does not really replace output(), but does terminate descent.
                }
                return null;
            }
        };
        for (Variable v : s.variables) v.transform (t);
    }

    public void generateCode (Path source) throws Exception
    {
        job.set ("Generating C++ code", "backendStatus");

        StringBuilder result = new StringBuilder ();
        RendererC context;
        if (T.equals ("int")) context = new RendererCfp (this, result);
        else                  context = new RendererC   (this, result);
        BackendDataC bed = (BackendDataC) digestedModel.backendData;

        if (tls) SIMULATOR = "Simulator<" + T + ">::instance->";
        else     SIMULATOR = "Simulator<" + T + ">::instance.";

        result.append ("#include \"runtime.h\"\n");
        if (kokkos)
        {
            result.append ("#include \"profiling.h\"\n");
        }
        result.append ("#include \"Matrix.tcc\"\n");
        result.append ("#include \"MatrixFixed.tcc\"\n");
        if (T.equals ("int"))
        {
            result.append ("#include \"fixedpoint.tcc\"\n");
        }
        for (ProvideOperator po : extensions)
        {
            Path include = po.include (this);
            if (include == null) continue;
            result.append ("#include <" + include.getFileName () + ">\n");
        }
        result.append ("\n");
        result.append ("#include <iostream>\n");
        result.append ("#include <vector>\n");
        result.append ("#include <cmath>\n");
        result.append ("#include <csignal>\n");
        result.append ("\n");
        generateClassList (digestedModel, result);
        result.append ("class Wrapper;\n");
        result.append ("\n");
        assignNames (context, digestedModel);
        generateDeclarations (digestedModel, result);
        result.append ("class Wrapper : public WrapperBase<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");
        result.append ("  " + prefix (digestedModel) + "_Population " + mangle (digestedModel.name) + ";\n");
        result.append ("\n");
        result.append ("  Wrapper ()\n");
        result.append ("  {\n");
        result.append ("    population = &" + mangle (digestedModel.name) + ";\n");
        result.append ("    " + mangle (digestedModel.name) + ".container = this;\n");
        if (bed.singleton)
        {
            result.append ("    " + mangle (digestedModel.name) + ".instance.container = this;\n");
        }
        result.append ("  }\n");
        result.append ("};\n");
        result.append ("Wrapper * wrapper;\n");
        result.append ("\n");

        StringBuilder vectorDefinitions = new StringBuilder ();
        if (lib)
        {
            // Generate a companion header file
            String name = source.getFileName ().toString ();
            int pos = name.lastIndexOf ('.');
            String stem = pos > 0 ? name.substring (0, pos) : name;

            Path headerPath = source.getParent ().resolve (stem + ".h");
            try (BufferedWriter writer = Files.newBufferedWriter (headerPath))
            {
                writer.append ("#ifndef " + stem + "_h\n");
                writer.append ("#define " + stem + "_h\n");
                writer.append ("\n");
                writer.append ("void init (int argc, char ** argv);\n");
                writer.append ("void run (" + T + " until);\n");
                writer.append ("void finish ();\n");
                generateIOvector (digestedModel, writer, vectorDefinitions);
                writer.append ("\n");
                writer.append ("#endif\n");
            }

            result.append ("#include \"" + stem + ".h\"\n");
            result.append ("\n");
        }

        result.append ("using namespace std;\n");
        result.append ("using namespace fl;\n");
        result.append ("\n");
        if (tls)
        {
            // Hack to make GCC 11.x happy. Should do no harm. Should also not be necessary.
            result.append ("template<class T> thread_local Simulator<T> * Simulator<T>::instance = 0;\n");
        }
        if (cli)
        {
            result.append ("Parameters<" + T + "> params;\n");
        }
        generateStatic (context);
        result.append ("\n");
        generateDefinitions (context, digestedModel);

        // Init IO
        // This function is used both during initial startup and also when launching worker threads.
        // In the latter case, calls to getHolder() should simply fill in an existing handle.
        // The initializers may do some redundant work in that case, but shouldn't do any harm.
        result.append ("void initIO ()\n");
        result.append ("{\n");
        generateMainInitializers (context);
        result.append ("}\n");
        result.append ("\n");

        // Init
        result.append ("void init (int argc, char * argv[])\n");
        result.append ("{\n");
        if (kokkos)
        {
            result.append ("  get_callbacks ();\n");
        }
        if (cli)
        {
            result.append ("  params.parse (argc, argv);\n");
        }
        if (T.equals ("int"))
        {
            Variable dt = digestedModel.find (new Variable ("$t", 1));
            result.append ("  Event<int>::exponent = " + dt.exponent + ";\n");
        }
        String integrator = digestedModel.metadata.getOrDefault ("Euler", "backend", "all", "integrator");
        if (integrator.equalsIgnoreCase ("RungeKutta")) integrator = "RungeKutta";
        else                                            integrator = "Euler";
        if (tls)
        {
            result.append ("  if (Simulator<" + T + ">::instance) delete Simulator<" + T + ">::instance;\n");
            result.append ("  Simulator<" + T + ">::instance = new Simulator<" + T + ">;\n");
        }
        result.append ("  " + SIMULATOR + "integrator = new " + integrator + "<" + T + ">;\n");
        result.append ("  " + SIMULATOR + "after = " + after + ";\n");
        result.append ("  initIO ();\n");
        result.append ("  wrapper = new Wrapper;\n");
        result.append ("  " + SIMULATOR + "init (wrapper);\n");
        result.append ("}\n");
        result.append ("\n");

        // Finish
        result.append ("void finish ()\n");
        result.append ("{\n");
        if (tls)
        {
            result.append ("  delete Simulator<" + T + ">::instance;\n");
        }
        else
        {
            result.append ("  " + SIMULATOR + "clear ();\n");
        }
        result.append ("  delete wrapper;\n");
        if (kokkos)
        {
            result.append ("  finalize_profiling ();\n");
        }
        result.append ("}\n");
        result.append ("\n");

        // Main
        if (lib)
        {
            result.append ("void run (" + T + " until)\n");
            result.append ("{\n");
            result.append ("  " + SIMULATOR + "run (until);\n");
            result.append ("}\n");
            if (! vectorDefinitions.isEmpty ())
            {
                result.append ("\n");
                result.append (vectorDefinitions.toString ());
            }
        }
        else
        {
            result.append ("int main (int argc, char * argv[])\n");
            result.append ("{\n");
            result.append ("  signal (SIGFPE,  signalHandler);\n");
            result.append ("  signal (SIGINT,  signalHandler);\n");
            result.append ("  signal (SIGTERM, signalHandler);\n");
            result.append ("\n");
            if (seed >= 0)
            {
                result.append ("  srand (" + seed + ");\n");
            }
            result.append ("  try\n");
            result.append ("  {\n");
            result.append ("    init (argc, argv);\n");
            result.append ("    " + SIMULATOR + "run ();\n");
            result.append ("    finish ();\n");
            result.append ("  }\n");
            result.append ("  catch (const char * message)\n");
            result.append ("  {\n");
            result.append ("    cerr << \"Exception: \" << message << endl;\n");
            result.append ("    return 1;\n");
            result.append ("  }\n");
            result.append ("  return 0;\n");
            result.append ("}\n");
        }

        Files.copy (new ByteArrayInputStream (result.toString ().getBytes ("UTF-8")), source);
    }

    public void generateClassList (EquationSet s, StringBuilder result)
    {
        for (EquationSet p : s.parts) generateClassList (p, result);
        result.append ("class " + prefix (s) + ";\n");
        result.append ("class " + prefix (s) + "_Population;\n");
    }

    public void assignNames (RendererC context, EquationSet s)
    {
        for (EquationSet p : s.parts) assignNames (context, p);

        context.setPart (s);
        BackendDataC bed = context.bed;

        class CheckStatic implements Visitor
        {
            public boolean global;
            public boolean visit (Operator op)
            {
                for (ProvideOperator po : extensions)
                {
                    Boolean result = po.assignNames (context, op);
                    if (result != null) return result;
                }
                if (op instanceof BuildMatrix)
                {
                    BuildMatrix m = (BuildMatrix) op;
                    m.name = "Matrix" + matrixNames.size ();
                    matrixNames.put (m, m.name);
                    return false;
                }
                if (op instanceof Constant)
                {
                    Constant constant = (Constant) op;
                    Type m = constant.value;
                    if (m instanceof Matrix)
                    {
                        constant.name = "Matrix" + matrixNames.size ();
                        matrixNames.put (constant, constant.name);
                        staticMatrix.add (constant);
                    }
                    return false;  // Don't try to descend tree from here
                }
                if (op instanceof Function)
                {
                    Function f = (Function) op;
                    if (f instanceof Output)  // Handle computed strings
                    {
                        Output o = (Output) f;
                        if (! o.hasColumnName)  // We need to auto-generate the column name.
                        {
                            o.columnName = "columnName" + stringNames.size ();
                            stringNames.put (op, o.columnName);
                            if (global)
                            {
                                bed.setGlobalNeedPath (s);
                                bed.globalColumns.add (o.columnName);
                            }
                            else
                            {
                                bed.setLocalNeedPath  (s);
                                bed.localColumns.add (o.columnName);
                            }
                        }
                        if (o.keywords != null)
                        {
                            for (Operator kv : o.keywords.values ())
                            {
                                if (kv instanceof Add)  // Mode is calculated
                                {
                                    Add a = (Add) kv;
                                    a.name = "columnMode" + stringNames.size ();
                                    stringNames.put (op, a.name);
                                }
                            }
                        }
                    }
                    // Detect functions that need static handles
                    if (f.operands.length > 0)
                    {
                        Operator operand0 = f.operands[0];
                        if (operand0 instanceof Constant)
                        {
                            Constant c = (Constant) operand0;
                            if (c.value instanceof Text)
                            {
                                String fileName = ((Text) c.value).value;
                                if (f instanceof ReadMatrix)
                                {
                                    ReadMatrix r = (ReadMatrix) f;
                                    r.name = matrixNames.get (fileName);
                                    if (r.name == null)
                                    {
                                        r.name = "Matrix" + matrixNames.size ();
                                        matrixNames.put (fileName, r.name);
                                        mainMatrix.add (r);
                                    }
                                }
                                else if (f instanceof Mfile)
                                {
                                    Mfile m = (Mfile) f;
                                    m.name = mfileNames.get (fileName);
                                    if (m.name == null)
                                    {
                                        m.name = "Mfile" + mfileNames.size ();
                                        mfileNames.put (fileName, m.name);
                                        mainMfile.add (m);
                                    }
                                }
                                else if (f instanceof Input)
                                {
                                    Input i = (Input) f;
                                    i.name = inputNames.get (fileName);
                                    if (i.name == null)
                                    {
                                        i.name = "Input" + inputNames.size ();
                                        inputNames.put (fileName, i.name);
                                        mainInput.add (i);
                                    }
                                }
                                else if (f instanceof Output)
                                {
                                    Output o = (Output) f;
                                    o.name = outputNames.get (fileName);
                                    if (o.name == null)
                                    {
                                        o.name = "Output" + outputNames.size ();
                                        outputNames.put (fileName, o.name);
                                        mainOutput.add (o);
                                    }
                                }
                            }
                        }
                        else  // Dynamic file name (no static handle)
                        {
                            boolean error = false;
                            if (f instanceof ReadMatrix)
                            {
                                ReadMatrix r = (ReadMatrix) f;
                                matrixNames.put (op,       r.name     = "Matrix"   + matrixNames.size ());
                                stringNames.put (operand0, r.fileName = "fileName" + stringNames.size ());
                                if (operand0 instanceof Add) ((Add) operand0).name = r.fileName;
                                else error = true;
                            }
                            else if (f instanceof Mfile)
                            {
                                Mfile m = (Mfile) f;
                                mfileNames .put (op,       m.name     = "Mfile"    + mfileNames .size ());
                                stringNames.put (operand0, m.fileName = "fileName" + stringNames.size ());
                                if (operand0 instanceof Add) ((Add) operand0).name = m.fileName;
                                else error = true;
                            }
                            else if (f instanceof Input)
                            {
                                Input i = (Input) f;
                                inputNames .put (op,       i.name     = "Input"    + inputNames .size ());
                                stringNames.put (operand0, i.fileName = "fileName" + stringNames.size ());
                                if (operand0 instanceof Add) ((Add) operand0).name = i.fileName;
                                else error = true;
                            }
                            else if (f instanceof Output)
                            {
                                Output o = (Output) f;
                                outputNames.put (op,       o.name     = "Output"   + outputNames.size ());
                                stringNames.put (operand0, o.fileName = "fileName" + stringNames.size ());
                                if (operand0 instanceof Add) ((Add) operand0).name = o.fileName;
                                else error = true;
                            }
                            if (error)
                            {
                                Backend.err.get ().println ("ERROR: File name must be a string expression.");
                                throw new AbortRun ();
                            }
                        }
                    }
                    return true;   // Functions could be nested, so continue descent.
                }
                return true;
            }
        }
        CheckStatic checkStatic = new CheckStatic ();
        for (Variable v : s.ordered)
        {
            checkStatic.global = v.hasAttribute ("global");
            v.visit (checkStatic);
        }
    }

    public void generateStatic (RendererC context)
    {
        StringBuilder result = context.result;
        String thread_local = tls ? "thread_local " : "";

        for (ProvideOperator po : extensions) po.generateStatic (context);
        for (Constant m : staticMatrix)
        {
            Matrix A = (Matrix) m.value;
            int rows = A.rows ();
            int cols = A.columns ();
            result.append ("MatrixFixed<" + T + "," + rows + "," + cols + "> " + m.name + " = {");
            String initializer = "";
            for (int c = 0; c < cols; c++)
            {
                for (int r = 0; r < rows; r++)
                {
                    initializer += context.print (A.get (r, c), m.exponent) + ", ";
                }
            }
            if (initializer.length () > 2) initializer = initializer.substring (0, initializer.length () - 2);
            result.append (initializer + "};\n");
        }
        for (ReadMatrix r : mainMatrix)
        {
            result.append (thread_local + "MatrixInput<" + T + "> * " + r.name + ";\n");
        }
        for (Mfile m : mainMfile)
        {
            result.append (thread_local + "Mfile<" + T + "> * " + m.name + ";\n");
        }
        for (Input i : mainInput)
        {
            result.append (thread_local + "InputHolder<" + T + "> * " + i.name + ";\n");
        }
        for (Output o : mainOutput)
        {
            result.append (thread_local + "OutputHolder<" + T + "> * " + o.name + ";\n");
        }
    }

    public void generateMainInitializers (RendererC context)
    {
        StringBuilder result = context.result;
        for (ProvideOperator po : extensions) po.generateMainInitializers (context);
        for (ReadMatrix r : mainMatrix)
        {
            result.append ("  " + r.name + " = matrixHelper<" + T + "> (\"" + r.operands[0].getString () + "\"");
            if (T.equals ("int")) result.append (", " + r.exponent);
            result.append (");\n");
        }
        for (Mfile m : mainMfile)
        {
            result.append ("  " + m.name + " = MfileHelper<" + T + "> (\"" + m.operands[0].getString () + "\");\n");
        }
        for (Input i : mainInput)
        {
            result.append ("  " + i.name + " = inputHelper<" + T + "> (\"" + i.operands[0].getString () + "\"");
            if (T.equals ("int")) result.append (", " + i.exponent);
            result.append (");\n");

            boolean smooth =             i.getKeywordFlag ("smooth");
            boolean time   = smooth  ||  i.getKeywordFlag ("time");
            if (time)   result.append ("  " + i.name + "->time = true;\n");
            if (smooth) result.append ("  " + i.name + "->smooth = true;\n");
        }
        for (Output o : mainOutput)
        {
            result.append ("  " + o.name + " = outputHelper<" + T + "> (\"" + o.operands[0].getString () + "\");\n");
        }
    }

    public void generateIOvector (EquationSet s, Writer writer, StringBuilder vectorDefinitions) throws IOException
    {
        for (EquationSet p : s.parts) generateIOvector (p, writer, vectorDefinitions);

        // Determine if any IO vectors are present
        boolean found = false;
        for (Variable v : s.ordered)
        {
            if (! v.getMetadata ().getFlag ("backend", "c", "vector")) continue;
            found = true;
            break;
        }
        if (! found) return;

        // Determine name and any indices needed to identify part.
        String pop  = "";
        String args = "";
        String f    = "";  // function body
        EquationSet c = s;           // child
        EquationSet p = s.container; // parent
        int i = 1;  // For any level #, p# is the population of that part, and i# is the index into the population. Level 0 is the population of s.
        while (p != null)
        {
            String cpop  = prefix (c) + "_Population";
            String cname = mangle (c.name);

            if (pop.isEmpty ()) pop = cname;
            else                pop = cname + "_" + pop;
            if (p.isSingleton ())
            {
                f = "  " + cpop + " & p" + (i-1) + " = p" + i + ".instance." + cname + ";\n" + f;
            }
            else  // Need to specify index into population
            {
                if (args.isEmpty ()) args = "int i" + i;
                else                 args = "int i" + i + ", " + args;
                f = "  " + cpop + " & p" + (i-1) + " = p" + i + ".instances[i" + i + "]->" + cname + ";\n" + f;
            }

            c = p;
            p = p.container;
            i++;
        }
        f = "  " + prefix (c) + "_Population & p" + (i-1) + " = wrapper->" + mangle (c.name) + ";\n" + f;

        if (! IOvectorWritten)
        {
            IOvectorWritten = true;
            writer.append ("\n");
            writer.append ("class IOvector\n");
            writer.append ("{\n");
            writer.append ("public:\n");
            writer.append ("  virtual int size () = 0;\n");
            writer.append ("  virtual " + T + " get  (int i) = 0;\n");
            writer.append ("  virtual void set (int i, " + T + " value) = 0;\n");
            writer.append ("};\n");
            writer.append ("\n");
        }

        for (Variable v : s.ordered)
        {
            if (! v.getMetadata ().getFlag ("backend", "c", "vector")) continue;

            // Emit a class definition for this specific population and variable.
            String var  = mangle (v.nameString ());
            String name = pop + var;
            String className = "IOvector" + name;
            vectorDefinitions.append ("class " + className + ": public IOvector\n");
            vectorDefinitions.append ("{\n");
            vectorDefinitions.append ("public:\n");
            vectorDefinitions.append ("  " + prefix (s) + "_Population * population;\n");
            if (! s.isSingleton ())
            {
                vectorDefinitions.append ("  virtual int size ()\n");
                vectorDefinitions.append ("  {\n");
                vectorDefinitions.append ("    return population->instances.size ();\n");  // This assumes dense population. We won't try to guard against null entries.
                vectorDefinitions.append ("  }\n");
            }
            vectorDefinitions.append ("  virtual " + T + " get (int i)\n");
            vectorDefinitions.append ("  {\n");
            vectorDefinitions.append ("    return population->instances.at (i)->" + var + ";\n");
            vectorDefinitions.append ("  }\n");

            vectorDefinitions.append ("  virtual void set (int i, " + T + " value)\n");
            vectorDefinitions.append ("  {\n");
            vectorDefinitions.append ("    population->instances.at (i)->" + var + " = value;\n");
            vectorDefinitions.append ("  }\n");
            vectorDefinitions.append ("};\n");
            vectorDefinitions.append ("\n");

            // A complete copy of the get() function, including code to locate population, will be emitted for each variable.
            // Since it's unlikely there will be more than one variable, this isn't too redundant.
            String prototype = "IOvector * get" + name + " (" + args + ")";
            vectorDefinitions.append (prototype + "\n");
            vectorDefinitions.append ("{\n");
            vectorDefinitions.append (f);
            vectorDefinitions.append ("\n");
            vectorDefinitions.append ("  " + className + " * result = new " + className + ";\n");
            vectorDefinitions.append ("  result->population = &p0;\n");
            vectorDefinitions.append ("  return result;\n");
            vectorDefinitions.append ("}\n");
            vectorDefinitions.append ("\n");

            writer.append (prototype + ";\n");
        }
    }

    /**
        Declares all classes, along with their member variables and functions.

        For each part, generates two classes: one for the instances ("local")
        and one for the population as a whole ("global"). Within each class,
        declares buffer classes for integration and derivation, then member
        variables, and finally member functions as appropriate.
    **/
    public void generateDeclarations (EquationSet s, StringBuilder result)
    {
        for (EquationSet p : s.parts) generateDeclarations (p, result);
        generateDeclarationsLocal (s, result);
        generateDeclarationsGlobal (s, result);
    }

    public void generateDeclarationsGlobal (EquationSet s, StringBuilder result)
    {
        BackendDataC bed = (BackendDataC) s.backendData;

        // Population class header
        result.append ("class " + prefix (s) + "_Population : public Population<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");

        // Population buffers
        if (bed.needGlobalDerivative)
        {
            result.append ("  class Derivative\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            result.append ("    Derivative * next;\n");
            result.append ("  };\n");
            result.append ("\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  class Preserve\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("    " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            result.append ("  };\n");
            result.append ("\n");
        }

        // Population variables
        if (bed.singleton)
        {
            result.append ("  " + prefix (s) + " instance;\n");
        }
        else
        {
            if (bed.n != null)
            {
                result.append ("  int n;\n");
            }
            if (bed.trackInstances)
            {
                result.append ("  std::vector<" + prefix (s) + " *> instances;\n");
            }
            else if (bed.index != null)  // The instances vector can supply the next index, so only declare nextIndex if instances was not declared.
            {
                result.append ("  int nextIndex;\n");
            }
            if (bed.newborn >= 0)
            {
                result.append ("  int firstborn;\n");
            }
        }
        if (bed.needGlobalDerivative)
        {
            result.append ("  Derivative * stackDerivative;\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  Preserve * preserve;\n");
        }
        for (Variable v : bed.globalMembers)
        {
            result.append ("  " + type (v) + " " + mangle (v) + ";\n");
        }
        for (Variable v : bed.globalBufferedExternal)
        {
            result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
        }
        for (String columnName : bed.globalColumns)
        {
            result.append ("  String " + columnName + ";\n");
        }
        if (! bed.globalFlagType.isEmpty ())
        {
            // This should come last, because it can affect alignment.
            result.append ("  " + bed.globalFlagType + " flags;\n");
        }
        result.append ("\n");

        // Population functions
        if (bed.needGlobalCtor)
        {
            result.append ("  " + prefix (s) + "_Population ();\n");
        }
        if (bed.needGlobalDtor)
        {
            result.append ("  virtual ~" + prefix (s) + "_Population ();\n");
        }
        if (! bed.singleton)
        {
            result.append ("  virtual Part<" + T + "> * create ();\n");
            if (bed.index != null)
            {
                result.append ("  virtual void add (Part<" + T + "> * part);\n");
                if (bed.trackInstances)
                {
                    result.append ("  virtual void remove (Part<" + T + "> * part);\n");
                }
            }
        }
        if (bed.needGlobalInit)
        {
            result.append ("  virtual void init ();\n");
        }
        if (bed.needGlobalIntegrate)
        {
            result.append ("  virtual void integrate ();\n");
        }
        if (bed.needGlobalUpdate)
        {
            result.append ("  virtual void update ();\n");
        }
        if (bed.needGlobalFinalize)
        {
            result.append ("  virtual bool finalize ();\n");
        }
        if (bed.canResize)
        {
            result.append ("  virtual void resize (int n);\n");
        }
        if (bed.n != null  &&  ! bed.singleton)
        {
            result.append ("  virtual int getN ();\n");
        }
        if (bed.needGlobalUpdateDerivative)
        {
            result.append ("  virtual void updateDerivative ();\n");
        }
        if (bed.needGlobalFinalizeDerivative)
        {
            result.append ("  virtual void finalizeDerivative ();\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  virtual void snapshot ();\n");
            result.append ("  virtual void restore ();\n");
        }
        if (bed.needGlobalDerivative)
        {
            result.append ("  virtual void pushDerivative ();\n");
            result.append ("  virtual void multiplyAddToStack (" + T + " scalar);\n");
            result.append ("  virtual void multiply (" + T + " scalar);\n");
            result.append ("  virtual void addToMembers ();\n");
        }
        if (bed.newborn >= 0)
        {
            result.append ("  virtual void clearNew ();\n");
        }
        if (s.connectionBindings != null)
        {
            result.append ("  virtual ConnectIterator<" + T + "> * getIterators ();\n");
            result.append ("  virtual ConnectPopulation<" + T + "> * getIterator (int i);\n");
        }
        if (bed.needGlobalPath)
        {
            result.append ("  virtual void path (String & result);\n");
        }

        // Population class trailer
        result.append ("};\n");
        result.append ("\n");
    }

    public void generateDeclarationsLocal (EquationSet s, StringBuilder result)
    {
        BackendDataC bed = (BackendDataC) s.backendData;

        // Unit class
        result.append ("class " + prefix (s) + " : public PartTime<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");

        // Unit buffers
        if (bed.needLocalDerivative)
        {
            result.append ("  class Derivative\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.localDerivative)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            result.append ("    Derivative * next;\n");
            result.append ("  };\n");
            result.append ("\n");
        }
        if (bed.needLocalPreserve)
        {
            result.append ("  class Preserve\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.localIntegrated)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.localDerivativePreserve)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.localBufferedExternalWriteDerivative)
            {
                result.append ("    " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            result.append ("  };\n");
            result.append ("\n");
        }

        // Unit variables
        if (bed.needLocalDerivative)
        {
            result.append ("  Derivative * stackDerivative;\n");
        }
        if (bed.needLocalPreserve)
        {
            result.append ("  Preserve * preserve;\n");
        }
        if (bed.pathToContainer == null)
        {
            result.append ("  " + prefix (s.container) + " * container;\n");
        }
        if (s.connectionBindings != null)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                // we should be able to assume that s.container is non-null; ie: a connection should always operate in some larger container
                result.append ("  " + prefix (c.endpoint) + " * " + mangle (c.alias) + ";\n");
            }
        }
        if (s.accountableConnections != null)
        {
            for (EquationSet.AccountableConnection ac : s.accountableConnections)
            {
                result.append ("  int " + prefix (ac.connection) + "_" + mangle (ac.alias) + "_count;\n");
            }
        }
        if (bed.refcount)
        {
            result.append ("  int refcount;\n");
        }
        if (bed.index != null)
        {
            result.append ("  int " + mangle ("$index") + ";\n");
        }
        if (bed.lastT)
        {
            result.append ("  " + T + " lastT;\n");  // $lastT is for internal use only, so no need for __24 prefix.
        }
        for (Variable v : bed.localMembers)
        {
            result.append ("  " + type (v) + " " + mangle (v) + ";\n");
        }
        for (Variable v : bed.localBufferedExternal)
        {
            result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
        }
        for (EquationSet p : s.parts)
        {
            result.append ("  " + prefix (p) + "_Population " + mangle (p.name) + ";\n");
        }
        for (String columnName : bed.localColumns)
        {
            result.append ("  String " + columnName + ";\n");
        }
        for (EventSource es : bed.eventSources)
        {
            String eventMonitor = "eventMonitor_" + prefix (es.target.container);
            if (es.monitorIndex > 0) eventMonitor += "_" + es.monitorIndex;
            result.append ("  std::vector<Part<" + T + "> *> " + eventMonitor + ";\n");
        }
        for (EventTarget et : bed.eventTargets)
        {
            if (! et.trackOne  &&  et.edge != EventTarget.NONZERO)
            {
                result.append ("  " + T + " " + mangle (et.track.name) + ";\n");
            }
            if (et.timeIndex >= 0)
            {
                result.append ("  " + T + " eventTime" + et.timeIndex + ";\n");
            }
        }
        if (! bed.localFlagType.isEmpty ())
        {
            result.append ("  " + bed.localFlagType + " flags;\n");
        }
        int i = 0;
        for (Delay d : bed.delays)
        {
            d.index = i++;
            result.append ("  DelayBuffer<" + T + "> delay" + d.index + ";\n");
        }
        result.append ("\n");

        // Unit functions
        if (bed.needLocalCtor)
        {
            result.append ("  " + prefix (s) + " ();\n");
        }
        if (bed.needLocalDtor)
        {
            result.append ("  virtual ~" + prefix (s) + " ();\n");
        }
        if (bed.localMembers.size () > 0)
        {
            result.append ("  virtual void clear ();\n");
        }
        if (s.container == null)
        {
            result.append ("  virtual void setPeriod (" + T + " dt);\n");
        }
        if (bed.needLocalDie)
        {
            result.append ("  virtual void die ();\n");
        }
        if (bed.localReference.size () > 0)
        {
            result.append ("  virtual void enterSimulation ();\n");
        }
        result.append ("  virtual void leaveSimulation ();\n");
        if (bed.refcount)
        {
            result.append ("  virtual bool isFree ();\n");
        }
        if (bed.needLocalInit)
        {
            result.append ("  virtual void init ();\n");
        }
        if (bed.needLocalIntegrate)
        {
            result.append ("  virtual void integrate ();\n");
        }
        if (bed.needLocalUpdate)
        {
            result.append ("  virtual void update ();\n");
        }
        if (bed.needLocalFinalize)
        {
            result.append ("  virtual bool finalize ();\n");
        }
        if (bed.needLocalUpdateDerivative)
        {
            result.append ("  virtual void updateDerivative ();\n");
        }
        if (bed.needLocalFinalizeDerivative)
        {
            result.append ("  virtual void finalizeDerivative ();\n");
        }
        if (bed.needLocalPreserve)
        {
            result.append ("  virtual void snapshot ();\n");
            result.append ("  virtual void restore ();\n");
        }
        if (bed.needLocalDerivative)
        {
            result.append ("  virtual void pushDerivative ();\n");
            result.append ("  virtual void multiplyAddToStack (" + T + " scalar);\n");
            result.append ("  virtual void multiply (" + T + " scalar);\n");
            result.append ("  virtual void addToMembers ();\n");
        }
        if (bed.live != null  &&  ! bed.live.hasAttribute ("constant"))
        {
            result.append ("  virtual " + T + " getLive ();\n");
        }
        if (bed.xyz != null  &&  s.connected)
        {
            result.append ("  virtual void getXYZ (MatrixFixed<" + T + ",3,1> & xyz);\n");
        }
        if (s.connectionBindings != null)
        {
            if (bed.p != null)
            {
                result.append ("  virtual " + T + " getP ();\n");
            }
            if (bed.hasProject)
            {
                result.append ("  virtual void getProject (int i, MatrixFixed<" + T + ",3,1> & xyz);\n");
            }
            result.append ("  virtual void setPart (int i, Part<" + T + "> * part);\n");
            result.append ("  virtual Part<" + T + "> * getPart (int i);\n");
        }
        if (bed.newborn >= 0)
        {
            result.append ("  virtual bool getNewborn ();\n");
        }
        if (s.connectionMatrix != null  &&  s.connectionMatrix.needsMapping)
        {
            result.append ("  virtual int mapIndex (int i, int rc);\n");
        }
        if (bed.eventTargets.size () > 0)
        {
            result.append ("  virtual bool eventTest (int i);\n");
            if (bed.needLocalEventDelay)
            {
                result.append ("  virtual " + T + " eventDelay (int i);\n");
            }
            result.append ("  virtual void setLatch (int i);\n");
            if (bed.eventReferences.size () > 0)
            {
                result.append ("  virtual void finalizeEvent ();\n");
            }
        }
        if (bed.accountableEndpoints.size () > 0)
        {
            result.append ("  virtual int getCount (int i);\n");
        }
        if (bed.needLocalPath)
        {
            result.append ("  virtual void path (String & result);\n");
        }

        // Conversions
        Set<Conversion> conversions = s.getConversions ();
        for (Conversion pair : conversions)
        {
            EquationSet source = pair.from;
            EquationSet dest   = pair.to;
            result.append ("  void " + mangle (source.name) + "_2_" + mangle (dest.name) + " (" + mangle (source.name) + " * from, int " + mangle ("$type") + ");\n");
        }

        // Unit class trailer
        result.append ("};\n");
        result.append ("\n");
    }

    public void generateDefinitions (RendererC context, EquationSet s) throws Exception
    {
        for (EquationSet p : s.parts) generateDefinitions (context, p);

        context.setPart (s);
        generateDefinitionsLocal (context);
        generateDefinitionsGlobal (context);
    }

    public void generateDefinitionsGlobal (RendererC context) throws Exception
    {
        EquationSet   s      = context.part;
        BackendDataC  bed    = context.bed;
        StringBuilder result = context.result;
        context.global = true;
        String ps = prefix (s);
        String ns = ps + "_Population::";  // namespace for all functions associated with part s

        // Population ctor
        if (bed.needGlobalCtor)
        {
            result.append (ns + ps + "_Population ()\n");
            result.append ("{\n");
            if (! bed.singleton)
            {
                if (bed.n != null)  // and not singleton, so trackN is true
                {
                    result.append ("  n = 0;\n");
                }
                if (! bed.trackInstances  &&  bed.index != null)
                {
                    result.append ("  nextIndex = 0;\n");
                }
                if (bed.newborn >= 0)
                {
                    result.append ("  firstborn = 0;\n");
                }
            }
            if (bed.needGlobalDerivative)
            {
                result.append ("  stackDerivative = 0;\n");
            }
            if (bed.needGlobalPreserve)
            {
                result.append ("  preserve = 0;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population dtor
        if (bed.needGlobalDtor)
        {
            result.append (ns + "~" + ps + "_Population ()\n");
            result.append ("{\n");
            if (bed.needGlobalDerivative)
            {
                result.append ("  while (stackDerivative)\n");
                result.append ("  {\n");
                result.append ("    Derivative * temp = stackDerivative;\n");
                result.append ("    stackDerivative = stackDerivative->next;\n");
                result.append ("    delete temp;\n");
                result.append ("  }\n");
            }
            if (bed.needGlobalPreserve)
            {
                result.append ("  if (preserve) delete preserve;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population create
        if (! bed.singleton)  // In the case of a singleton, this will remain a pure virtual function, and throw an exception if called.
        {
            result.append ("Part<" + T + "> * " + ns + "create ()\n");
            result.append ("{\n");
            result.append ("  " + ps + " * p = new " + ps + ";\n");
            if (bed.pathToContainer == null) result.append ("  p->container = (" + prefix (s.container) + " *) container;\n");
            result.append ("  return p;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population add / remove
        if (bed.index != null  &&  ! bed.singleton)
        {
            result.append ("void " + ns + "add (Part<" + T + "> * part)\n");
            result.append ("{\n");
            result.append ("  " + ps + " * p = (" + ps + " *) part;\n");
            if (bed.trackInstances)
            {
                result.append ("  if (p->" + mangle ("$index") + " < 0)\n");
                result.append ("  {\n");
                result.append ("    p->" + mangle ("$index") + " = instances.size ();\n");
                result.append ("    instances.push_back (p);\n");
                result.append ("  }\n");
                result.append ("  else\n");
                result.append ("  {\n");
                result.append ("    instances[p->" + mangle ("$index") + "] = p;\n");
                result.append ("  }\n");
                if (bed.newborn >= 0)
                {
                    result.append ("  p->flags = (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
                    result.append ("  firstborn = min (firstborn, p->" + mangle ("$index") + ");\n");
                }
            }
            else
            {
                result.append ("  if (p->" + mangle ("$index") + " < 0) p->" + mangle ("$index") + " = nextIndex++;\n");
            }
            result.append ("}\n");
            result.append ("\n");

            if (bed.trackInstances)
            {
                result.append ("void " + ns + "remove (Part<" + T + "> * part)\n");
                result.append ("{\n");
                result.append ("  " + ps + " * p = (" + ps + " *) part;\n");
                result.append ("  instances[p->" + mangle ("$index") + "] = 0;\n");
                result.append ("  Population<" + T + ">::remove (part);\n");
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Population init
        if (bed.needGlobalInit)
        {
            result.append ("void " + ns + "init ()\n");
            result.append ("{\n");
            s.setInit (1);
            //   Zero out members
            for (Variable v : bed.globalMembers)
            {
                result.append ("  " + zero (mangle (v), v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternal)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
                result.append ("  " + clearAccumulator (mangle (         v), v, context) + ";\n");
            }
            if (! bed.globalFlagType.isEmpty ())
            {
                result.append ("  flags = 0;\n");
            }
            //   Compute variables
            if (bed.nInitOnly)  // $n is not stored, so we need to declare a local variable to receive its value.
            {
                result.append ("  " + type (bed.n) + " " + mangle (bed.n) + ";\n");
            }
            s.simplify ("$init", bed.globalInit);
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.globalInit);
            EquationSet.determineOrderInit (bed.globalInit);
            for (Variable v : bed.globalInit)
            {
                multiconditional (v, context, "  ");
            }
            //   create instances
            if (bed.singleton)
            {
                if (bed.newborn >= 0)
                {
                    result.append ("  instance.flags = (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
                }
                result.append ("  instance.enterSimulation ();\n");
                result.append ("  container->getEvent ()->enqueue (&instance);\n");
                result.append ("  instance.init ();\n");
            }
            else
            {
                if (bed.n != null)  // and not singleton, so trackN is true
                {
                    result.append ("  resize (" + resolve (bed.n.reference, context, bed.nInitOnly));
                    if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                    result.append (");\n");
                }
            }
            //   make connections
            if (s.connectionBindings != null)
            {
                result.append ("  " + SIMULATOR + "connect (this);\n");  // queue to evaluate our connections
            }
            s.setInit (0);
            result.append ("}\n");
            result.append ("\n");
        }

        // Population integrate
        if (bed.needGlobalIntegrate)
        {
            result.append ("void " + ns + "integrate ()\n");
            result.append ("{\n");
            push_region (result, ns + "integrate()");
            result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
            context.hasEvent = true;
            result.append ("  " + T + " dt = event->dt;\n");
            result.append ("  if (preserve)\n");
            result.append ("  {\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + resolve (v.reference, context, false) + " = preserve->" + mangle (v) + " + ");
                // For fixed-point:
                // raw result = exponentDerivative+exponentTime-MSB
                // shift = raw-exponentVariable = exponentDerivative+exponentTime-MSB-exponentVariable
                int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                if (shift != 0  &&  T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                }
                else
                {
                    result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                }
            }
            result.append ("  }\n");
            result.append ("  else\n");
            result.append ("  {\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + resolve (v.reference, context, false) + " += ");
                int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                if (shift != 0  &&  T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                }
                else
                {
                    result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                }
            }
            result.append ("  }\n");
            context.hasEvent = false;
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Population update
        if (bed.needGlobalUpdate)
        {
            result.append ("void " + ns + "update ()\n");
            result.append ("{\n");
            push_region (result, ns + "update()");
            for (Variable v : bed.globalBufferedInternalUpdate)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            s.simplify ("$live", bed.globalUpdate);
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.globalUpdate);
            for (Variable v : bed.globalUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.globalBufferedInternalUpdate)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Population finalize
        if (bed.needGlobalFinalize)
        {
            result.append ("bool " + ns + "finalize ()\n");
            result.append ("{\n");

            if (bed.canResize  &&  bed.n.derivative == null  &&  bed.canGrowOrDie)  // $n shares control with other specials, so must coordinate with them
            {
                // $n may be assigned during the regular update cycle, so we need to monitor it.
                result.append ("  if (" + mangle ("$n") + " != " + mangle ("next_", "$n") + ") " + SIMULATOR + "resize (this, max (0, " + mangle ("next_", "$n"));
                if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                result.append ("));\n");
                result.append ("  else " + SIMULATOR + "resize (this, -1);\n");
            }

            for (Variable v : bed.globalBufferedExternal)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWrite)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }

            // Return value is ignored except for top-level population.
            boolean returnN = bed.needGlobalFinalizeN;
            if (bed.canResize)  
            {
                if (bed.canGrowOrDie)
                {
                    if (bed.n.derivative != null)  // $n' exists
                    {
                        // the rate of change in $n is pre-determined, so it relentlessly overrides any other structural dynamics
                        if (returnN)
                        {
                            result.append ("  if (n == 0) return false;\n");
                            returnN = false;
                        }
                        result.append ("  " + SIMULATOR + "resize (this, max (0, " + mangle ("$n"));
                        if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                        result.append ("));\n");
                    }
                }
                else  // $n is the only kind of structural dynamics, so simply do a resize() when needed
                {
                    if (returnN)
                    {
                        result.append ("  if (n == 0) return false;\n");
                        returnN = false;
                    }
                    result.append ("  int floorN = max (0, ");
                    if (context.useExponent) result.append (mangle ("$n") + context.printShift (bed.n.exponent - Operator.MSB));
                    else                     result.append ("(int) " + mangle ("$n"));
                    result.append (");\n");
                    result.append ("  if (n != floorN) " + SIMULATOR + "resize (this, floorN);\n");
                }
            }

            if (returnN)
            {
                // returnN can be true even if s is a singleton, because the top-level
                // part can have lethal $p but only create one instance. In that (very common)
                // case, $p controls lifespan of simulation. This also means we can't
                // use $n to indicate that the population is dead. The alternative is
                // to use $live.
                if (bed.singleton) result.append ("  return " + resolve (bed.live.reference, context, false, "instance.", false) + ";\n");
                else               result.append ("  return n;\n");
            }
            else
            {
                result.append ("  return true;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population resize()
        if (bed.canResize)
        {
            result.append ("void " + ns + "resize (int n)\n");
            result.append ("{\n");
            if (bed.canGrowOrDie  &&  bed.n.derivative == null)
            {
                result.append ("  if (n < 0)\n");
                result.append ("  {\n");
                result.append ("    " + mangle ("$n") + " = this->n;\n");
                result.append ("    return;\n");
                result.append ("  }\n");
                result.append ("\n");
            }
            result.append ("  Population<" + T + ">::resize (n);\n");
            result.append ("\n");
            result.append ("  for (int i = instances.size () - 1; this->n > n  &&  i >= 0; i--)\n");
            result.append ("  {\n");
            result.append ("    Part * p = instances[i];\n");
            result.append ("    if (p  &&  p->getLive ()) p->die ();\n");
            result.append ("  }\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population getN
        if (bed.trackN)
        {
            result.append ("int " + ns + "getN ()\n");
            result.append ("{\n");
            result.append ("  return n;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population updateDerivative
        if (bed.needGlobalUpdateDerivative)
        {
            result.append ("void " + ns + "updateDerivative ()\n");
            result.append ("{\n");
            push_region (result, ns + "updateDerivative()");
            for (Variable v : bed.globalBufferedInternalDerivative)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            s.simplify ("$live", bed.globalDerivativeUpdate);  // This is unlikely to make any difference. Just being thorough before call to multiconditional().
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.globalDerivativeUpdate);
            for (Variable v : bed.globalDerivativeUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.globalBufferedInternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Population finalizeDerivative
        if (bed.needGlobalFinalizeDerivative)
        {
            result.append ("void " + ns + "finalizeDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalBufferedExternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.needGlobalPreserve)
        {
            // Population snapshot
            result.append ("void " + ns + "snapshot ()\n");
            result.append ("{\n");
            result.append ("  preserve = new Preserve;\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  preserve->" + mangle ("next_", v) + " = " + mangle ("next_", v) + ";\n");
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Population restore
            result.append ("void " + ns + "restore ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("  " + mangle (v) + " = preserve->" + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  " + mangle ("next_", v) + " = preserve->" + mangle ("next_", v) + ";\n");
            }
            result.append ("  delete preserve;\n");
            result.append ("  preserve = 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.needGlobalDerivative)
        {
            // Population pushDerivative
            result.append ("void " + ns + "pushDerivative ()\n");
            result.append ("{\n");
            result.append ("  Derivative * temp = new Derivative;\n");
            result.append ("  temp->_next = stackDerivative;\n");
            result.append ("  stackDerivative = temp;\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  temp->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Population multiplyAddToStack
            result.append ("void " + ns + "multiplyAddToStack (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  stackDerivative->" + mangle (v) + " += ");
                if (T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ");\n");
                }
                else
                {
                    result.append (                      mangle (v) + " * scalar;\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Population multiply
            result.append ("void " + ns + "multiply (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                if (T.equals ("int"))
                {
                    result.append ("  " + mangle (v) + " = (int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ";\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " *= scalar;\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Population addToMembers
            result.append ("void " + ns + "addToMembers ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  " + mangle (v) + " += stackDerivative->" + mangle (v) + ";\n");
            }
            result.append ("  Derivative * temp = stackDerivative;\n");
            result.append ("  stackDerivative = stackDerivative->next;\n");
            result.append ("  delete temp;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population clearNew
        if (bed.newborn >= 0)
        {
            result.append ("void " + ns + "clearNew ()\n");
            result.append ("{\n");
            result.append ("  flags &= ~((" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + ");\n");  // Reset our clearNew flag
            if (bed.singleton)
            {
                result.append ("  instance.flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.newborn + ");\n");
            }
            else
            {
                result.append ("  int count = instances.size ();\n");
                result.append ("  for (int i = firstborn; i < count; i++)\n");
                result.append ("  {\n");
                result.append ("    " + ps + " * p = instances[i];\n");
                result.append ("    if (p) p->flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.newborn + ");\n");
                result.append ("  }\n");
                result.append ("  firstborn = count;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population getIterators
        if (s.connectionBindings != null)
        {
            class ConnectionHolder
            {
                public Operator      k;
                public Operator      min;
                public Operator      max;
                public Operator      radius;
                public boolean       hasProject;
                public EquationSet   endpoint;
                public List<Integer> indices = new ArrayList<Integer> ();
                public List<Object>  resolution;

                public boolean equivalent (Operator a, Operator b)
                {
                    if (a == b) return true;
                    if (a == null  ||  b == null) return false;
                    return a.equals (b);
                }

                public boolean equals (Object o)
                {
                    ConnectionHolder that = (ConnectionHolder) o;  // This is a safe assumption, since this is a local class.
                    return    equivalent (k,      that.k)
                           && equivalent (min,    that.min)
                           && equivalent (max,    that.max)
                           && equivalent (radius, that.radius)
                           && hasProject == that.hasProject
                           && endpoint   == that.endpoint;
                }

                public void emit ()
                {
                    for (Integer index : indices)
                    {
                        result.append ("    case " + index + ":\n");
                    }
                    result.append ("    {\n");
                    if (k == null  &&  radius == null)
                    {
                        result.append ("      result = new ConnectPopulation<" + T + "> (i);\n");
                    }
                    else
                    {
                        result.append ("      result = new ConnectPopulationNN<" + T + "> (i);\n");  // Pulls in KDTree dependencies, for full NN support.
                    }

                    boolean testK      = false;
                    boolean testRadius = false;
                    boolean constantKR = false;

                    if (k != null)
                    {
                        result.append ("      result->k = ");
                        k.render (context);
                        result.append (";\n");
                        testK = true;
                        if (k instanceof Constant)
                        {
                            Constant c = (Constant) k;
                            if (c.value instanceof Scalar  &&  ((Scalar) c.value).value != 0) constantKR = true;
                        }
                    }
                    if (max != null)
                    {
                        result.append ("      result->Max = ");
                        max.render (context);
                        result.append (";\n");
                    }
                    if (min != null)
                    {
                        result.append ("      result->Min = ");
                        min.render (context);
                        result.append (";\n");
                    }
                    if (radius != null)
                    {
                        result.append ("      result->radius = ");
                        radius.render (context);
                        result.append (";\n");
                        testRadius = true;
                        if (radius instanceof Constant)
                        {
                            Constant c = (Constant) radius;
                            if (c.value instanceof Scalar  &&  ((Scalar) c.value).value != 0) constantKR = true;
                        }
                    }
                    if (hasProject)
                    {
                        result.append ("      result->rank += 1;");
                    }
                    if (constantKR)
                    {
                        result.append ("      result->rank -= 2;\n");
                    }
                    else
                    {
                        if (testK  &&  testRadius)
                        {
                            result.append ("      if (result->k > 0  ||  result->radius > 0) result->rank -= 2;\n");
                        }
                        else if (testK)
                        {
                            result.append ("      if (result->k > 0) result->rank -= 2;\n");
                        }
                        else if (testRadius)
                        {
                            result.append ("      if (result->radius > 0) result->rank -= 2;\n");
                        }
                    }

                    assembleInstances (s, "", resolution, 0, "      ", result);
                    result.append ("      result->size = result->instances->size ();\n");

                    result.append ("      break;\n");
                    result.append ("    }\n");
                }
            }

            List<ConnectionHolder> connections = new ArrayList<ConnectionHolder> ();
            boolean needNN = false;  // TODO: Should determine this across the entire simulation, so that only one of getIteratorsSimple() or getIteratorsNN() is linked.
            for (ConnectionBinding c : s.connectionBindings)
            {
                ConnectionHolder h = new ConnectionHolder ();

                Variable v = s.find (new Variable (c.alias + ".$k"));
                EquationEntry e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.k = e.expression;

                v = s.find (new Variable (c.alias + ".$max"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.max = e.expression;

                v = s.find (new Variable (c.alias + ".$min"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.min = e.expression;

                v = s.find (new Variable (c.alias + ".$radius"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.radius = e.expression;

                h.hasProject = s.find (new Variable (c.alias + ".$project")) != null;
                h.endpoint = c.endpoint;

                int i = connections.indexOf (h);
                if (i < 0)
                {
                    connections.add (h);
                    h.resolution = c.resolution;
                }
                else
                {
                    h = connections.get (i);
                }
                h.indices.add (c.index);

                if (h.k != null  ||  h.radius != null) needNN = true;
            }


            result.append ("ConnectIterator<" + T + "> * " + ns + "getIterators ()\n");
            result.append ("{\n");
            if (s.connectionMatrix == null)
            {
                if (needNN)
                {
                    result.append ("  return getIteratorsNN ();\n");
                }
                else
                {
                    result.append ("  return getIteratorsSimple ();\n");
                }
            }
            else
            {
                ConnectionMatrix cm = s.connectionMatrix;
                result.append ("  ConnectPopulation<" + T + "> * rows = getIterator (" + cm.rows.index + ");\n");
                result.append ("  ConnectPopulation<" + T + "> * cols = getIterator (" + cm.cols.index + ");\n");
                result.append ("  " + ps + " * dummy = (" + ps + " *) create ();\n");  // Will be deleted when ConnectMatrix is deleted.
                result.append ("  dummy->setPart (" + cm.rows.index + ", (*rows->instances)[0]);\n");
                result.append ("  dummy->setPart (" + cm.cols.index + ", (*cols->instances)[0]);\n");
                result.append ("  dummy->getP ();\n");  // We don't actually want $p. This just forces "dummy" to initialize any local matrix variables.

                // Create iterator
                result.append ("  IteratorNonzero<" + T + "> * it = ");
                boolean found = false;
                for (ProvideOperator po : extensions)
                {
                    if (po.getIterator (cm.A, context))
                    {
                        found = true;
                        break;
                    }
                }
                if (! found  &&  cm.A instanceof AccessElement)
                {
                    AccessElement ae = (AccessElement) cm.A;
                    Operator op0 = ae.operands[0];
                    result.append ("::getIterator (");
                    if (op0 instanceof AccessVariable)
                    {
                        AccessVariable av = (AccessVariable) op0;
                        Variable v = av.reference.variable;
                        if (v.hasAttribute ("temporary"))
                        {
                            // Just assume that v is an alias for ReadMatrix.
                            // Also, matrix must be a static object. Enforced by AccessElement.hasCorrectForm().
                            ReadMatrix r = (ReadMatrix) v.equations.first ().expression;
                            result.append (r.name + "->A");
                        }
                        else
                        {
                            if (! v.hasAttribute ("MatrixPointer")) result.append ("& ");
                            context.global = false;
                            result.append (resolve (av.reference, context, true, "dummy->", false));  // Actually an rvalue, but we claim lvalue to finesse resolve() into not adding dereference for matrix pointer.
                            context.global = true;
                        }
                    }
                    else  // Must be a constant. Enforced by AccessElement.hasCorrectForm().
                    {
                        Constant c = (Constant) op0;
                        result.append (c.name);
                    }
                    result.append (");\n");
                }

                result.append ("  return new ConnectMatrix<" + T + "> (rows, cols, " + cm.rows.index + ", " + cm.cols.index + ", it, dummy);\n");
            }
            result.append ("}\n");
            result.append ("\n");


            result.append ("ConnectPopulation<" + T + "> * " + ns + "getIterator (int i)\n");
            result.append ("{\n");
            result.append ("  ConnectPopulation<" + T + "> * result = 0;\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionHolder h : connections) h.emit ();
            result.append ("  }\n");
            result.append ("  return result;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population path
        if (bed.needGlobalPath)
        {
            result.append ("void " + ns + "path (String & result)\n");
            result.append ("{\n");
            if (((BackendDataC) s.container.backendData).needLocalPath)  // Will our container provide a non-empty path?
            {
                result.append ("  container->path (result);\n");
                result.append ("  result += \"." + s.name + "\";\n");
            }
            else
            {
                result.append ("  result = \"" + s.name + "\";\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }
    }

    public void generateDefinitionsLocal (RendererC context) throws Exception
    {
        EquationSet   s      = context.part;
        BackendDataC  bed    = context.bed;
        StringBuilder result = context.result;
        context.global = false;
        String ns = prefix (s) + "::";

        // Unit ctor
        if (bed.needLocalCtor)
        {
            result.append (ns + prefix (s) + " ()\n");
            result.append ("{\n");
            if (bed.needLocalDerivative)
            {
                result.append ("  stackDerivative = 0;\n");
            }
            if (bed.needLocalPreserve)
            {
                result.append ("  preserve = 0;\n");
            }
            for (EquationSet p : s.parts)
            {
                result.append ("  " + mangle (p.name) + ".container = this;\n");
                BackendDataC pbed = (BackendDataC) p.backendData;
                if (pbed.singleton)
                {
                    result.append ("  " + mangle (p.name) + ".instance.container = this;\n");
                }
            }
            if (s.accountableConnections != null)
            {
                for (EquationSet.AccountableConnection ac : s.accountableConnections)
                {
                    result.append ("  int " + prefix (ac.connection) + "_" + mangle (ac.alias) + "_count = 0;\n");
                }
            }
            if (bed.refcount)
            {
                result.append ("  refcount = 0;\n");
            }
            if (bed.index != null)
            {
                result.append ("  " + mangle ("$index") + " = -1;\n");  // -1 indicates that an index needs to be assigned. This should only be done once.
            }
            if (bed.localMembers.size () > 0)
            {
                result.append ("  clear ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit dtor
        if (bed.needLocalDtor)
        {
            result.append (ns + "~" + prefix (s) + " ()\n");
            result.append ("{\n");
            if (bed.needLocalDerivative)
            {
                result.append ("  while (stackDerivative)\n");
                result.append ("  {\n");
                result.append ("    Derivative * temp = stackDerivative;\n");
                result.append ("    stackDerivative = stackDerivative->next;\n");
                result.append ("    delete temp;\n");
                result.append ("  }\n");
            }
            if (bed.needLocalPreserve)
            {
                result.append ("  if (preserve) delete preserve;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit clear
        if (bed.localMembers.size () > 0)
        {
            result.append ("void " + ns + "clear ()\n");
            result.append ("{\n");
            for (Variable v : bed.localMembers)
            {
                if (v.hasAttribute ("MatrixPointer")) continue;
                result.append ("  " + zero (mangle (v), v) + ";\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit setPeriod
        if (s.container == null)  // instance of top-level population, so set period on wrapper whenever our period changes
        {
            result.append ("void " + ns + "setPeriod (" + T + " dt)\n");
            result.append ("{\n");
            result.append ("  PartTime<" + T + ">::setPeriod (dt);\n");
            result.append ("  if (container->visitor->event != visitor->event) container->setPeriod (dt);\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit die
        if (bed.needLocalDie)
        {
            result.append ("void " + ns + "die ()\n");
            result.append ("{\n");

            if (s.metadata.getFlag ("backend", "all", "fastExit"))
            {
                result.append ("  " + SIMULATOR + "stop = true;\n");
            }
            else
            {
                // tag part as dead
                if (bed.liveFlag >= 0)  // $live is stored in this part
                {
                    result.append ("  flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ");\n");
                }

                // instance counting
                if (bed.n != null  &&  ! bed.singleton) result.append ("  container->" + mangle (s.name) + ".n--;\n");

                for (String alias : bed.accountableEndpoints)
                {
                    result.append ("  " + mangle (alias) + "->" + prefix (s) + "_" + mangle (alias) + "_count--;\n");
                }

                // release event monitors
                for (EventTarget et : bed.eventTargets)
                {
                    for (EventSource es : et.sources)
                    {
                        String part = "";
                        if (es.reference != null) part = resolveContainer (es.reference, context, "");
                        String eventMonitor = "eventMonitor_" + prefix (s);
                        if (es.monitorIndex > 0) eventMonitor += "_" + es.monitorIndex;
                        result.append ("  removeMonitor (" + part + eventMonitor + ", this);\n");
                    }
                }
            }

            result.append ("}\n");
            result.append ("\n");
        }

        // Unit enterSimulation
        if (bed.localReference.size () > 0)
        {
            result.append ("void " + ns + "enterSimulation ()\n");
            result.append ("{\n");
            TreeSet<String> touched = new TreeSet<String> ();  // String rather than EquationSet, because we may have references to several different instances of the same EquationSet, and all must be accounted
            for (VariableReference r : bed.localReference)
            {
                String container = resolveContainer (r, context, "");
                if (touched.add (container)) result.append ("  " + container + "refcount++;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit leaveSimulation
        {
            result.append ("void " + ns + "leaveSimulation ()\n");
            result.append ("{\n");
            if (! bed.singleton)
            {
                result.append ("  " + containerOf (s, false, "") + mangle (s.name) + ".remove (this);\n");
            }
            TreeSet<String> touched = new TreeSet<String> ();
            for (VariableReference r : bed.localReference)
            {
                String container = resolveContainer (r, context, "");
                if (touched.add (container)) result.append ("  " + container + "refcount--;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit isFree
        if (bed.refcount)
        {
            result.append ("bool " + ns + "isFree ()\n");
            result.append ("{\n");
            result.append ("  return refcount == 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit init
        if (bed.needLocalInit)
        {
            result.append ("void " + ns + "init ()\n");
            result.append ("{\n");
            s.setInit (1);

            for (Variable v : bed.localBufferedExternal)
            {
                // Clear both buffered and regular values, so we can use a proper combiner during init.
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
                result.append ("  " + clearAccumulator (mangle (         v), v, context) + ";\n");
            }
            for (EventTarget et : bed.eventTargets)
            {
                if (et.timeIndex >= 0)
                {
                    result.append ("  eventTime" + et.timeIndex + " = 10;\n");  // Normal values are modulo 1 second. This initial value guarantees no match.
                }
                // Auxiliary variables get initialized as part of the regular list below.
            }
            if (! bed.localFlagType.isEmpty ())
            {
                if (bed.liveFlag >= 0)
                {
                    if (bed.newborn >= 0)
                    {
                        result.append ("  flags |= (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ";\n");
                    }
                    else
                    {
                        result.append ("  flags = (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ";\n");
                    }
                }
                else
                {
                    if (bed.newborn < 0)
                    {
                        result.append ("  flags = 0;\n");
                    }
                    // else flags has already been initialized by Population::add()
                }
            }

            // Initialize static objects
            for (Variable v : bed.localInit)  // non-optimized list, so hopefully all variables are covered
            {
                for (EquationEntry e : v.equations)
                {
                    prepareStaticObjects (e.expression, context, "  ");
                    if (e.condition != null) prepareStaticObjects (e.condition, context, "  ");
                }
            }

            // Compute variables
            if (bed.lastT)
            {
                result.append ("  lastT = " + SIMULATOR + "currentEvent->t;\n");
            }
            s.simplify ("$init", bed.localInit);
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.localInit);
            EquationSet.determineOrderInit (bed.localInit);
            if (bed.localInit.contains (bed.dt))
            {
                result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                context.hasEvent = true;
                result.append ("  " + type (bed.dt) + " " + mangle (bed.dt) + ";\n");
            }
            for (Variable v : bed.localInit)  // optimized list: only variables with equations that actually fire during init
            {
                multiconditional (v, context, "  ");
            }
            // TODO: may need to deal with REPLACE for buffered variables. See internal.Part
            if (bed.localInit.contains (bed.dt))
            {
                result.append ("  if (" + mangle (bed.dt) + " != event->dt) setPeriod (" + mangle (bed.dt) + ");\n");
            }
            else if (bed.setDt)  // implies that bed.dt exists and is constant
            {
                result.append ("  setPeriod (" + resolve (bed.dt.reference, context, false) + ");\n");
            }

            // instance counting
            if (bed.trackN) result.append ("  " + containerOf (s, false, "") + mangle (s.name) + ".n++;\n");

            for (String alias : bed.accountableEndpoints)
            {
                result.append ("  " + mangle (alias) + "->" + prefix (s) + "_" + mangle (alias) + "_count++;\n");
            }

            // Request event monitors
            for (EventTarget et : bed.eventTargets)
            {
                for (EventSource es : et.sources)
                {
                    String part = "";
                    if (es.reference != null) part = resolveContainer (es.reference, context, "");
                    String eventMonitor = "eventMonitor_" + prefix (s);
                    if (es.monitorIndex > 0) eventMonitor += "_" + es.monitorIndex;
                    result.append ("  " + part + eventMonitor + ".push_back (this);\n");
                }
            }

            // contained populations
            if (s.parts.size () > 0)
            {
                // If there are parts at all, then orderedParts must be filled in correctly. Otherwise it may be null.
                for (EquationSet e : s.orderedParts)
                {
                    if (((BackendDataC) e.backendData).needGlobalInit)
                    {
                        result.append ("  " + mangle (e.name) + ".init ();\n");
                    }
                }
            }

            s.setInit (0);
            context.hasEvent = false;
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit integrate
        if (bed.needLocalIntegrate)
        {
            result.append ("void " + ns + "integrate ()\n");
            result.append ("{\n");
            push_region (result, ns + "integrate()");
            if (bed.localIntegrated.size () > 0)
            {
                if (bed.lastT)
                {
                    result.append ("  " + T + " dt = " + SIMULATOR + "currentEvent->t - lastT;\n");
                }
                else
                {
                    result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                    context.hasEvent = true;
                    result.append ("  " + T + " dt = event->dt;\n");
                }
                // Note the resolve() call on the left-hand-side below has lvalue==false.
                // Integration always takes place in the primary storage of a variable.
                String pad = "";
                if (bed.needLocalPreserve)
                {
                    pad = "  ";
                    result.append ("  if (preserve)\n");
                    result.append ("  {\n");
                    for (Variable v : bed.localIntegrated)
                    {
                        result.append ("    " + resolve (v.reference, context, false) + " = preserve->" + mangle (v) + " + ");
                        int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                        if (shift != 0  &&  T.equals ("int"))
                        {
                            result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                        }
                        else
                        {
                            result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                        }
                    }
                    result.append ("  }\n");
                    result.append ("  else\n");
                    result.append ("  {\n");
                }
                for (Variable v : bed.localIntegrated)
                {
                    result.append (pad + "  " + resolve (v.reference, context, false) + " += ");
                    int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                    }
                    else
                    {
                        result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                    }
                }
                if (bed.needLocalPreserve) result.append ("  }\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalIntegrate)
                {
                    result.append ("  " + mangle (e.name) + ".integrate ();\n");
                }
            }
            context.hasEvent = false;
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit update
        if (bed.needLocalUpdate)
        {
            result.append ("void " + ns + "update ()\n");
            result.append ("{\n");
            push_region (result, ns + "update()");
            for (Variable v : bed.localBufferedInternalUpdate)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            s.simplify ("$live", bed.localUpdate);
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.localUpdate);
            for (Variable v : bed.localUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.localBufferedInternalUpdate)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalUpdate)
                {
                    result.append ("  " + mangle (e.name) + ".update ();\n");
                }
            }
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit finalize
        if (bed.needLocalFinalize)
        {
            result.append ("bool " + ns + "finalize ()\n");
            result.append ("{\n");

            // contained populations
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalFinalize)
                {
                    result.append ("  " + mangle (e.name) + ".finalize ();\n");  // ignore return value
                }
            }

            // Early-out if we are already dead
            if (bed.liveFlag >= 0)  // $live is stored in this part
            {
                result.append ("  if (! (flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ")) return false;\n");  // early-out if we are already dead, to avoid another call to die()
            }

            // Preemptively fetch current event
            boolean needT = bed.eventSources.size () > 0  ||  s.lethalP  ||  bed.localBufferedExternal.contains (bed.dt);
            if (needT)
            {
                result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                context.hasEvent = true;
            }

            // Events
            for (EventSource es : bed.eventSources)
            {
                EventTarget et = es.target;
                String eventMonitor = "eventMonitor_" + prefix (et.container);
                if (es.monitorIndex > 0) eventMonitor += "_" + es.monitorIndex;

                if (es.testEach)
                {
                    result.append ("  for (Part * p : " + eventMonitor + ")\n");
                    result.append ("  {\n");
                    result.append ("    if (! p->eventTest (" + et.valueIndex + ")) continue;\n");
                    eventGenerate ("    ", et, context, false);
                    result.append ("  }\n");
                }
                else  // All monitors share same condition, so only test one.
                {
                    result.append ("  if (! " + eventMonitor + ".empty ()  &&  " + eventMonitor + "[0]->eventTest (" + et.valueIndex + "))\n");
                    result.append ("  {\n");
                    if (es.delayEach)  // Each target instance may require a different delay.
                    {
                        result.append ("    for (auto p : " + eventMonitor + ")\n");
                        result.append ("    {\n");
                        eventGenerate ("      ", et, context, false);
                        result.append ("    }\n");
                    }
                    else  // All delays are the same.
                    {
                        eventGenerate ("    ", et, context, true);
                    }
                    result.append ("  }\n");
                }
            }
            int eventCount = bed.eventTargets.size ();
            if (eventCount > 0)
            {
                result.append ("  flags &= ~(" + bed.localFlagType + ") 0 << " + eventCount + ";\n");
            }

            // Finalize variables
            if (bed.lastT)
            {
                result.append ("  lastT = " + SIMULATOR + "currentEvent->t;\n");
            }
            for (Variable v : bed.localBufferedExternal)
            {
                if (v == bed.dt)
                {
                    result.append ("  if (" + mangle ("next_", v) + " != event->dt) setPeriod (" + mangle ("next_", v) + ");\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
                }
            }
            for (Variable v : bed.localBufferedExternalWrite)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }

            if (bed.type != null)
            {
                result.append ("  switch (" + mangle ("$type") + ")\n");
                result.append ("  {\n");
                // Each "split" is one particular set of new parts to transform into.
                // Each combination requires a separate piece of code. Thus, the outer
                // structure here is a switch statement. Each case within the switch implements
                // a particular combination of new parts. At this point, $type merely indicates
                // which combination to process. Afterward, it will be set to an index within that
                // combination, per the N2A language document.
                int countSplits = s.splits.size ();
                for (int i = 0; i < countSplits; i++)
                {
                    ArrayList<EquationSet> split = s.splits.get (i);

                    // Check if $type = me. Ignore this particular case, since it is a null operation
                    if (split.size () == 1  &&  split.get (0) == s)
                    {
                        continue;
                    }

                    result.append ("    case " + (i + 1) + ":\n");
                    result.append ("    {\n");
                    boolean used = false;  // indicates that this instance is one of the resulting parts
                    int countParts = split.size ();
                    for (int j = 0; j < countParts; j++)
                    {
                        EquationSet to = split.get (j);
                        if (to == s  &&  ! used)
                        {
                            used = true;
                            result.append ("      " + mangle ("$type") + " = " + (j + 1) + ";\n");
                        }
                        else
                        {
                            result.append ("      " + containerOf (s, false, "") + mangle (s.name) + "_2_" + mangle (to.name) + " (this, " + (j + 1) + ");\n");
                        }
                    }
                    if (used)
                    {
                        result.append ("      break;\n");
                    }
                    else
                    {
                        result.append ("      die ();\n");
                        result.append ("      return false;\n");
                    }
                    result.append ("    }\n");
                }
                result.append ("  }\n");
            }

            if (s.lethalP)
            {
                // lethalP implies that $p exists, so no need to check for null
                if (bed.p.hasAttribute ("constant"))
                {
                    double pvalue = ((Scalar) ((Constant) bed.p.equations.first ().expression).value).value;
                    if (pvalue != 0)
                    {
                        // If $t' is exactly 1, then pow() is unnecessary here. However, that is a rare situation.
                        result.append ("  if (pow (" + resolve (bed.p.reference, context, false) + ", " + resolve (bed.dt.reference, context, false));
                        if (context.useExponent)
                        {
                            result.append (context.printShift (bed.dt.exponent - 15));  // second operand must have exponent=15
                            result.append (", " + bed.p.exponent);  // exponentA
                            result.append (", " + bed.p.exponent);  // exponentResult
                        }
                        result.append (") < uniform<" + T + "> ()");
                        if (context.useExponent) result.append (context.printShift (-1 - bed.p.exponent));  // -1 is hard-coded from the Uniform function.
                        result.append (")\n");
                    }
                }
                else
                {
                    if (bed.p.hasAttribute ("temporary"))
                    {
                        // Assemble a minimal set of expressions to evaluate $p
                        List<Variable> list = new ArrayList<Variable> ();
                        for (Variable t : s.ordered) if (t.hasAttribute ("temporary")  &&  bed.p.dependsOn (t) != null) list.add (t);
                        list.add (bed.p);
                        s.simplify ("$live", list, bed.p);
                        if (T.equals ("int")) EquationSet.determineExponentsSimplified (list);
                        for (Variable v : list)
                        {
                            multiconditional (v, context, "  ");
                        }
                    }

                    result.append ("  if (" + mangle ("$p") + " <= 0  ||  " + mangle ("$p") + " < " + context.print (1, bed.p.exponent) + "  &&  pow (" + mangle ("$p") + ", " + resolve (bed.dt.reference, context, false));
                    if (context.useExponent)
                    {
                        result.append (context.printShift (bed.dt.exponent - 15));
                        result.append (", " + bed.p.exponent);
                        result.append (", " + bed.p.exponent);
                    }
                    result.append (") < uniform<" + T + "> ()");
                    if (context.useExponent) result.append (context.printShift (-1 - bed.p.exponent));
                    result.append (")\n");
                }
                result.append ("  {\n");
                result.append ("    die ();\n");
                result.append ("    return false;\n");
                result.append ("  }\n");
            }

            if (s.lethalConnection)
            {
                for (ConnectionBinding c : s.connectionBindings)
                {
                	VariableReference r = s.resolveReference (c.alias + ".$live");
                	if (! r.variable.hasAttribute ("constant"))
                	{
                        result.append ("  if (" + resolve (r, context, false, "", true) + " == 0)\n");
                        result.append ("  {\n");
                        result.append ("    die ();\n");
                        result.append ("    return false;\n");
                        result.append ("  }\n");
                	}
                }
            }

            if (s.lethalContainer)
            {
                VariableReference r = s.resolveReference ("$up.$live");
                if (! r.variable.hasAttribute ("constant"))
                {
                    result.append ("  if (" + resolve (r, context, false, "", true) + " == 0)\n");
                    result.append ("  {\n");
                    result.append ("    die ();\n");
                    result.append ("    return false;\n");
                    result.append ("  }\n");
                }
            }

            result.append ("  return true;\n");
            context.hasEvent = false;
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit updateDerivative
        if (bed.needLocalUpdateDerivative)
        {
            result.append ("void " + ns + "updateDerivative ()\n");
            result.append ("{\n");
            push_region (result, ns + "updateDerivative()");
            for (Variable v : bed.localBufferedInternalDerivative)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            s.simplify ("$live", bed.localDerivativeUpdate);
            if (T.equals ("int")) EquationSet.determineExponentsSimplified (bed.localDerivativeUpdate);
            for (Variable v : bed.localDerivativeUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.localBufferedInternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalUpdateDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".updateDerivative ();\n");
                }
            }
            pop_region (result);
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit finalizeDerivative
        if (bed.needLocalFinalizeDerivative)
        {
            result.append ("void " + ns + "finalizeDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.localBufferedExternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.localBufferedExternalWriteDerivative)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalFinalizeDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".finalizeDerivative ();\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.needLocalPreserve)
        {
            // Unit snapshot
            result.append ("void " + ns + "snapshot ()\n");
            result.append ("{\n");
            if (bed.needLocalPreserve)
            {
                result.append ("  preserve = new Preserve;\n");
                for (Variable v : bed.localIntegrated)
                {
                    result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
                for (Variable v : bed.localDerivativePreserve)
                {
                    result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
                for (Variable v : bed.localBufferedExternalWriteDerivative)
                {
                    result.append ("  preserve->" + mangle ("next_", v) + " = " + mangle ("next_", v) + ";\n");
                    result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalPreserve)
                {
                    result.append ("  " + mangle (e.name) + ".snapshot ();\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit restore
            result.append ("void " + ns + "restore ()\n");
            result.append ("{\n");
            if (bed.needLocalPreserve)
            {
                for (Variable v : bed.localDerivativePreserve)
                {
                    result.append ("  " + mangle (v) + " = preserve->" + mangle (v) + ";\n");
                }
                for (Variable v : bed.localBufferedExternalWriteDerivative)
                {
                    result.append ("  " + mangle ("next_", v) + " = preserve->" + mangle ("next_", v) + ";\n");
                }
                result.append ("  delete preserve;\n");
                result.append ("  preserve = 0;\n");
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalPreserve)
                {
                    result.append ("  " + mangle (e.name) + ".restore ();\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.needLocalDerivative)
        {
            // Unit pushDerivative
            result.append ("void " + ns + "pushDerivative ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                result.append ("  Derivative * temp = new Derivative;\n");
                result.append ("  temp->next = stackDerivative;\n");
                result.append ("  stackDerivative = temp;\n");
                for (Variable v : bed.localDerivative)
                {
                    result.append ("  temp->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".pushDerivative ();\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit multiplyAddToStack
            result.append ("void " + ns + "multiplyAddToStack (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.localDerivative)
            {
                result.append ("  stackDerivative->" + mangle (v) + " += ");
                if (T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ");\n");
                }
                else
                {
                    result.append (                      mangle (v) + " * scalar;\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".multiplyAddToStack (scalar);\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit multiply
            result.append ("void " + ns + "multiply (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.localDerivative)
            {
                if (T.equals ("int"))
                {
                    result.append ("  " + mangle (v) + " = (int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ";\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " *= scalar;\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".multiply (scalar);\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit addToMembers
            result.append ("void " + ns + "addToMembers ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                for (Variable v : bed.localDerivative)
                {
                    result.append ("  " + mangle (v) + " += stackDerivative->" + mangle (v) + ";\n");
                }
                result.append ("  Derivative * temp = stackDerivative;\n");
                result.append ("  stackDerivative = stackDerivative->next;\n");
                result.append ("  delete temp;\n");
            }
            for (EquationSet e : s.parts)
            {
                if (((BackendDataC) e.backendData).needGlobalDerivative)
                {
                    result.append ("  " + mangle (e.name) + ".addToMembers ();\n");
                }
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit setPart
        if (s.connectionBindings != null)
        {
            result.append ("void " + ns + "setPart (int i, Part * part)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ": " + mangle (c.alias) + " = (" + prefix (c.endpoint) + " *) part; return;\n");
            }
            result.append ("  }\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getPart
        if (s.connectionBindings != null)
        {
            result.append ("Part<" + T + "> * " + ns + "getPart (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ": return " + mangle (c.alias) + ";\n");
            }
            result.append ("  }\n");
            result.append ("  return 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getCount
        if (bed.accountableEndpoints.size () > 0)
        {
            result.append ("int " + ns + "getCount (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                if (bed.accountableEndpoints.contains (c.alias))
                {
                    result.append ("    case " + c.index + ": return " + mangle (c.alias) + "->" + prefix (s) + "_" + mangle (c.alias) + "_count;\n");
                }
            }
            result.append ("  }\n");
            result.append ("  return 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getProject
        if (bed.hasProject)
        {
            result.append ("void " + ns + "getProject (int i, MatrixFixed<" + T + ",3,1> & xyz)\n");
            result.append ("{\n");

            // $project is evaluated similar to $p. The result is not stored.
            s.setConnect (1);

            result.append ("  switch (i)\n");
            result.append ("  {\n");
            boolean needDefault = false;
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ":");
                Variable project = s.find (new Variable (c.alias + ".$project"));
                if (project == null)  // fetch $xyz from endpoint
                {
                    VariableReference fromXYZ = s.resolveReference (c.alias + ".$xyz");
                    if (fromXYZ.variable == null)
                    {
                        needDefault = true;
                    }
                    else
                    {
                        if (fromXYZ.variable.hasAttribute ("temporary"))  // calculated value
                        {
                            result.append (" " + mangle (c.alias) + "->getXYZ (xyz); break;\n");
                        }
                        else  // stored value or "constant"
                        {
                            result.append (" xyz = " + resolve (fromXYZ, context, false) + "; break;\n");
                        }
                    }
                }
                else  // compute $project
                {
                    result.append ("\n");  // to complete the "case" line
                    result.append ("    {\n");
                    if (project.hasAttribute ("temporary"))  // it could also be "constant", but no other type
                    {
                        // Assemble a minimal set of expressions to evaluate $project
                        List<Variable> list = new ArrayList<Variable> ();
                        for (Variable t : s.ordered)
                        {
                            if ((t.hasAttribute ("temporary")  ||  bed.localMembers.contains (t))  &&  project.dependsOn (t) != null) list.add (t);
                        }
                        list.add (project);
                        s.simplify ("$connect", list, project);
                        if (T.equals ("int")) EquationSet.determineExponentsSimplified (list);
                        for (Variable v : list)
                        {
                            multiconditional (v, context, "      ");
                        }
                    }
                    result.append ("      xyz = " + resolve (project.reference, context, false) + ";\n");
                    result.append ("      break;\n");
                    result.append ("    }\n");
                }
            }
            if (needDefault)
            {
                result.append ("    default:\n");
                result.append ("      xyz[0] = 0;\n");
                result.append ("      xyz[1] = 0;\n");
                result.append ("      xyz[2] = 0;\n");
            }
            result.append ("  }\n");
            result.append ("}\n");

            s.setConnect (0);
        }

        // Unit mapIndex
        if (s.connectionMatrix != null  &&  s.connectionMatrix.needsMapping)
        {
            result.append ("int " + ns + "mapIndex (int i, int rc)\n");
            result.append ("{\n");

            Variable rc = new Variable ("rc");
            rc.reference = new VariableReference ();
            rc.reference.variable = rc;
            rc.container = s;
            rc.addAttribute ("preexistent");
            AccessVariable av = new AccessVariable ();
            av.reference = rc.reference;

            ConnectionMatrix cm = s.connectionMatrix;
            cm.rowMapping.replaceRC (av);
            cm.colMapping.replaceRC (av);

            result.append ("  if (i == " + cm.rows.index + ") return ");
            cm.rowMapping.rhs.render (context);
            result.append (";\n");
            result.append ("  return ");
            cm.colMapping.rhs.render (context);
            result.append (";\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getNewborn
        if (bed.newborn >= 0)
        {
            result.append ("bool " + ns + "getNewborn ()\n");
            result.append ("{\n");
            result.append ("  return flags & (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getLive
        if (bed.live != null  &&  ! bed.live.hasAttribute ("constant"))
        {
            result.append (T + " " + ns + "getLive ()\n");
            result.append ("{\n");
            if (! bed.live.hasAttribute ("accessor"))  // "accessor" indicates whether or not $value is actually stored
            {
                result.append ("  if (" + resolve (bed.live.reference, context, false, "", true) + " == 0) return 0;\n");
            }
            if (s.lethalConnection)
            {
                for (ConnectionBinding c : s.connectionBindings)
                {
                    VariableReference r = s.resolveReference (c.alias + ".$live");
                    if (! r.variable.hasAttribute ("constant"))
                    {
                        result.append ("  if (" + resolve (r, context, false, "", true) + " == 0) return 0;\n");
                    }
                }
            }
            if (s.lethalContainer)
            {
                VariableReference r = s.resolveReference ("$up.$live");
                if (! r.variable.hasAttribute ("constant"))
                {
                    result.append ("  if (" + resolve (r, context, false, "", true) + " == 0) return 0;\n");
                }
            }
            result.append ("  return 1;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getP
        if (bed.p != null  &&  s.connectionBindings != null)  // Only connections need to provide an accessor
        {
            result.append (T + " " + ns + "getP ()\n");
            result.append ("{\n");
            s.setConnect (1);
            if (! bed.p.hasAttribute ("constant"))
            {
                // Assemble a minimal set of expressions to evaluate $p
                List<Variable> list = new ArrayList<Variable> ();
                for (Variable t : s.ordered)
                {
                    if ((t.hasAttribute ("temporary")  ||  bed.localMembers.contains (t))  &&  bed.p.dependsOn (t) != null) list.add (t);
                }
                list.add (bed.p);
                s.simplify ("$connect", list, bed.p);
                if (T.equals ("int")) EquationSet.determineExponentsSimplified (list);
                for (Variable v : list)
                {
                    multiconditional (v, context, "  ");
                }
            }
            result.append ("  return " + resolve (bed.p.reference, context, false) + ";\n");
            s.setConnect (0);
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getXYZ
        if (bed.xyz != null  &&  s.connected)  // Connection targets need to provide an accessor.
        {
            result.append ("void " + ns + "getXYZ (MatrixFixed<" + T + ",3,1> & xyz)\n");
            result.append ("{\n");
            // $xyz is either stored, "temporary", or "constant"
            // If "temporary", then we compute it on the spot.
            // If "constant", then we use the static matrix created during variable analysis
            // If stored, then simply copy into the return value.
            if (bed.xyz.hasAttribute ("temporary"))
            {
                // Assemble a minimal set of expressions to evaluate $xyz
                List<Variable> list = new ArrayList<Variable> ();
                for (Variable t : s.ordered) if (t.hasAttribute ("temporary")  &&  bed.xyz.dependsOn (t) != null) list.add (t);
                list.add (bed.xyz);
                s.simplify ("$live", list, bed.xyz);  // evaluate in $live phase, because endpoints already exist when connection is evaluated.
                if (T.equals ("int")) EquationSet.determineExponentsSimplified (list);
                for (Variable v : list)
                {
                    multiconditional (v, context, "    ");
                }
            }
            result.append ("  xyz = " + resolve (bed.xyz.reference, context, false) + ";\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit events
        if (bed.eventTargets.size () > 0)
        {
            result.append ("bool " + ns + "eventTest (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (EventTarget et : bed.eventTargets)
            {
                result.append ("    case " + et.valueIndex + ":\n");
                result.append ("    {\n");
                // Not safe or useful to simplify et.dependencies before emitting.
                for (Variable v : et.dependencies)
                {
                    multiconditional (v, context, "      ");
                }
                if (et.edge != EventTarget.NONZERO)
                {
                    result.append ("      " + T + " before = ");
                    if (et.trackOne) result.append (resolve (et.track.reference, context, false));
                    else             result.append (mangle (et.track.name));
                    result.append (";\n");
                }
                if (et.trackOne)  // This is a single variable, so check its value directly.
                {
                    result.append ("      " + T + " after = " + resolve (et.track.reference, context, true) + ";\n");
                }
                else  // This is an expression, so use our private auxiliary variable.
                {
                    result.append ("      " + T + " after = ");
                    et.event.operands[0].render (context);
                    result.append (";\n");
                    if (et.edge != EventTarget.NONZERO)
                    {
                        result.append ("      " + mangle (et.track.name) + " = after;\n");
                    }
                }
                switch (et.edge)
                {
                    case EventTarget.NONZERO:
                        if (et.timeIndex >= 0)
                        {
                            // Guard against multiple events in a given cycle.
                            // Note that other trigger types don't need this because they set the auxiliary variable,
                            // so the next test in the same cycle will no longer see change.
                            result.append ("      if (after == 0) return false;\n");
                            if (T.equals ("int"))
                            {
                                result.append ("      " + T + " moduloTime = " + SIMULATOR + "currentEvent->t;\n");  // No need for modulo arithmetic. Rather, int time should be wrapped elsewhere.
                            }
                            else  // float, double
                            {
                                result.append ("      " + T + " moduloTime = (" + T + ") fmod (" + SIMULATOR + "currentEvent->t, 1);\n");  // Wrap time at 1 second, to fit in float precision.
                            }
                            result.append ("      if (eventTime" + et.timeIndex + " == moduloTime) return false;\n");
                            result.append ("      eventTime" + et.timeIndex + " = moduloTime;\n");
                            result.append ("      return true;\n");
                        }
                        else
                        {
                            result.append ("      return after != 0;\n");
                        }
                        break;
                    case EventTarget.CHANGE:
                        result.append ("      return before != after;\n");
                        break;
                    case EventTarget.FALL:
                        result.append ("      return before != 0  &&  after == 0;\n");
                        break;
                    case EventTarget.RISE:
                    default:
                        result.append ("      return before == 0  &&  after != 0;\n");
                }
                result.append ("    }\n");
            }
            result.append ("  }\n");
            result.append ("  return false;\n");
            result.append ("}\n");
            result.append ("\n");

            if (bed.needLocalEventDelay)
            {
                result.append (T + " " + ns + "eventDelay (int i)\n");
                result.append ("{\n");
                result.append ("  switch (i)\n");
                result.append ("  {\n");
                for (EventTarget et : bed.eventTargets)
                {
                    if (et.delay >= -1) continue;

                    // Need to evaluate expression
                    result.append ("    case " + et.valueIndex + ":\n");
                    result.append ("    {\n");
                    for (Variable v : et.dependencies)
                    {
                        multiconditional (v, context, "      ");
                    }
                    result.append ("      " + T + " result = ");
                    et.event.operands[1].render (context);
                    result.append (";\n");
                    result.append ("      if (result < 0) return -1;\n");
                    result.append ("      return result;\n");
                    result.append ("    }\n");
                }
                result.append ("  }\n");
                result.append ("  return -1;\n");
                result.append ("}\n");
                result.append ("\n");
            }

            result.append ("void " + ns + "setLatch (int i)\n");
            result.append ("{\n");
            result.append ("  flags |= (" + bed.localFlagType + ") 0x1 << i;\n");
            result.append ("}\n");
            result.append ("\n");

            if (bed.eventReferences.size () > 0)
            {
                result.append ("void " + ns + "finalizeEvent ()\n");
                result.append ("{\n");
                for (Variable v : bed.eventReferences)
                {
                    String current  = resolve (v.reference, context, false);
                    String buffered = resolve (v.reference, context, true);
                    result.append ("  " + current);
                    switch (v.assignment)
                    {
                        case Variable.ADD:
                            result.append (" += " + buffered + ";\n");
                            result.append ("  " + zero (buffered, v) + ";\n");
                            break;
                        case Variable.MULTIPLY:
                        case Variable.DIVIDE:
                        {
                            // The current and buffered values of the variable have the same exponent.
                            // raw = exponentV + exponentV - MSB
                            // shift = raw - exponentV = exponentV - MSB
                            int shift = v.exponent - Operator.MSB;
                            if (shift != 0  &&  T.equals ("int"))
                            {
                                result.append (" = (int64_t) " + current + " * " + buffered + context.printShift (shift) + ";\n");
                            }
                            else
                            {
                                result.append (" *= " + buffered + ";\n");
                            }
                            result.append ("  " + clear (buffered, v, 1, context) + ";\n");
                            break;
                        }
                        case Variable.MIN:
                            result.append (" = min (" + current + ", " + buffered + ");\n");  // TODO: Write elementwise min() and max() for matrices.
                            result.append ("  " + clear (buffered, v, Double.POSITIVE_INFINITY, context) + ";\n");
                            break;
                        case Variable.MAX:
                            result.append (" = max (" + current + ", " + buffered + ");\n");
                            result.append ("  " + clear (buffered, v, Double.NEGATIVE_INFINITY, context) + ";\n");
                            break;
                        default:  // REPLACE
                            result.append (" = " + buffered + ";\n");
                            break;
                    }
                }
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Unit path
        if (bed.needLocalPath)
        {
            result.append ("void " + ns + "path (String & result)\n");
            result.append ("{\n");
            if (s.connectionBindings == null  ||  s.connectionBindings.size () == 1)  // Not a connection, or a unary connection
            {
                // We assume that result is passed in as the empty string.
                if (s.container != null)
                {
                    if (((BackendDataC) s.container.backendData).needLocalPath)  // Will our container provide a non-empty path?
                    {
                        result.append ("  container->path (result);\n");
                        result.append ("  result += \"." + s.name + "\";\n");
                    }
                    else
                    {
                        result.append ("  result = \"" + s.name + "\";\n");
                    }
                }
                if (bed.index != null)
                {
                    result.append ("  result += " + mangle ("$index") + ";\n");
                }
                else if (s.connectionBindings != null)
                {
                    ConnectionBinding c = s.connectionBindings.get (0);
                    BackendDataC cbed = (BackendDataC) c.endpoint.backendData;
                    if (cbed.index != null) result.append ("  result += " + mangle (c.alias) + "->" + mangle ("$index") + ";\n");
                }
            }
            else  // binary or higher connection
            {
                boolean first = true;
                boolean temp  = false;
                for (ConnectionBinding c : s.connectionBindings)
                {
                    if (first)
                    {
                        result.append ("  " + mangle (c.alias) + "->path (result);\n");
                        first = false;
                    }
                    else
                    {
                        if (! temp)
                        {
                            result.append ("  String temp;\n");
                            temp = true;
                        }
                        result.append ("  " + mangle (c.alias) + "->path (temp);\n");
                        result.append ("  result += \"-\";\n");
                        result.append ("  result += temp;\n");
                    }
                }
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit conversions
        Set<Conversion> conversions = s.getConversions ();
        for (Conversion pair : conversions)
        {
            EquationSet source = pair.from;
            EquationSet dest   = pair.to;
            boolean connectionSource = source.connectionBindings != null;
            boolean connectionDest   = dest  .connectionBindings != null;
            if (connectionSource != connectionDest)
            {
                Backend.err.get ().println ("Can't change $type between connection and non-connection.");
                throw new Backend.AbortRun ();
                // Why not? Because a connection *must* know the instances it connects, while
                // a compartment cannot know those instances. Thus, one can never be converted
                // to the other.
            }

            // The "2" functions only have local meaning, so they are never virtual.
            // Must do everything init() normally does, including increment $n.
            // Parameters:
            //   from -- the source part
            //   visitor -- the one managing the source part
            //   $type -- The integer index, in the $type expression, of the current target part. The target part's $type field will be initialized with this number (and zeroed after one cycle).
            result.append ("void " + ns + mangle (source.name) + "_2_" + mangle (dest.name) + " (" + mangle (source.name) + " * from, int " + mangle ("$type") + ")\n");
            result.append ("{\n");
            result.append ("  " + mangle (dest.name) + " * to = " + mangle (dest.name) + ".allocate ();\n");  // if this is a recycled part, then clear() is called
            if (connectionDest)
            {
                // Match connection bindings
                for (ConnectionBinding c : dest.connectionBindings)
                {
                    ConnectionBinding d = source.findConnection (c.alias);
                    if (d == null)
                    {
                        Backend.err.get ().println ("Unfulfilled connection binding during $type change.");
                        throw new Backend.AbortRun ();
                    }
                    result.append ("  to->" + mangle (c.alias) + " = from->" + mangle (c.alias) + ";\n");
                }
            }
            // TODO: Convert contained populations from matching populations in the source part?
            result.append ("  to->enterSimulation ();\n");
            result.append ("  getEvent ()->enqueue (to);\n");

            // Match variables between the two sets.
            // TODO: a match between variables should be marked as a dependency. This might change some "dummy" variables into stored values.
            String [] forbiddenAttributes = new String [] {"global", "constant", "accessor", "reference", "temporary", "dummy", "preexistent"};
            for (Variable v : dest.variables)
            {
                if (v.name.equals ("$type"))
                {
                    result.append ("  to->" + mangle (v) + " = " + mangle ("$type") + ";\n");  // initialize new part with its position in the $type split
                    continue;
                }
                if (v.hasAny (forbiddenAttributes)) continue;
                Variable v2 = source.find (v);
                if (v2 != null)
                {
                    result.append ("  to->" + mangle (v) + " = " + resolve (v2.reference, context, false, "from->", false) + ";\n");
                }
            }
            result.append ("  to->init ();\n");  // Unless the user qualifies code with $type, the values just copied above will simply be overwritten.

            result.append ("}\n");
            result.append ("\n");
        }
    }

    public void push_region (StringBuilder result, String name)
    {
        if (kokkos) result.append ("push_region (\"" + name +"\");\n");
    }

    public void pop_region (StringBuilder result)
    {
        if (kokkos) result.append ("pop_region ();\n");
    }

    public void eventGenerate (String pad, EventTarget et, RendererC context, boolean multi)
    {
        String eventSpike = "EventSpike";
        if (multi) eventSpike += "Multi";
        else       eventSpike += "Single";
        String eventSpikeLatch = eventSpike + "Latch<" + T + ">";
        eventSpike += "<" + T + ">";

        StringBuilder result = context.result;
        if (et.delay >= -1)  // delay is a constant, so do all tests at the Java level
        {
            if (et.delay < 0)  // timing is no-care
            {
                result.append (pad + eventSpike + " * spike = new " + eventSpikeLatch + ";\n");
                result.append (pad + "spike->t = " + SIMULATOR + "currentEvent->t;\n");  // queue immediately after current cycle, so latches get set for next full cycle
            }
            else if (et.delay == 0)  // process as close to current cycle as possible
            {
                result.append (pad + eventSpike + " * spike = new " + eventSpike + ";\n");  // fully execute the event (not latch it)
                result.append (pad + "spike->t = " + SIMULATOR + "currentEvent->t;\n");  // queue immediately
            }
            else
            {
                boolean quantizedConstant = false;  // Indicates the delay is known constant and falls on a regular time-step.
                Variable dt = context.part.findDt ();
                if (dt != null  &&  dt.hasAttribute ("constant"))  // constant $t'
                {
                    double quantum = ((Scalar) dt.type).value;
                    double ratio   = et.delay / quantum;
                    int    step    = (int) Math.round (ratio);
                    quantizedConstant = Math.abs (ratio - step) < 1e-3;
                    if (quantizedConstant)
                    {
                        double delay = step * quantum;
                        result.append (pad + eventSpike + " * spike = new " + (during ? eventSpikeLatch : eventSpike) + ";\n");
                        result.append (pad + "spike->t = " + SIMULATOR + "currentEvent->t + " + context.print (delay, dt.exponent) + ";\n");
                    }
                }

                if (! quantizedConstant)
                {
                    int exponent = context.part.getRoot ().find (new Variable ("$t", 1)).exponent;
                    result.append (pad + T + " delay = " + context.print (et.delay, exponent) + ";\n");
                    result.append (pad + eventSpike + " * spike;\n");
                    eventGenerate (pad, et, context, eventSpike, eventSpikeLatch);
                }
            }
        }
        else  // delay must be evaluated, so emit tests at C level
        {
            result.append (pad + T + " delay = p->eventDelay (" + et.valueIndex + ");\n");
            result.append (pad + eventSpike + " * spike;\n");
            result.append (pad + "if (delay < 0)\n");
            result.append (pad + "{\n");
            result.append (pad + "  " + eventSpike + " * spike = new " + eventSpikeLatch + ";\n");
            result.append (pad + "  spike->t = " + SIMULATOR + "currentEvent->t;\n");
            result.append (pad + "}\n");
            result.append (pad + "else if (delay == 0)\n");
            result.append (pad + "{\n");
            result.append (pad + "  " + eventSpike + " * spike = new " + eventSpike + ";\n");
            result.append (pad + "  spike->t = " + SIMULATOR + "currentEvent->t;\n");
            result.append (pad + "}\n");
            result.append (pad + "else\n");
            result.append (pad + "{\n");
            eventGenerate (pad + "  ", et, context, eventSpike, eventSpikeLatch);
            result.append (pad + "}\n");
        }

        result.append (pad + "spike->latch = " + et.valueIndex + ";\n");
        if (multi) result.append (pad + "spike->targets = &eventMonitor_" + prefix (et.container) + ";\n");
        else       result.append (pad + "spike->target = p;\n");
        result.append (pad + "" + SIMULATOR + "queueEvent.push (spike);\n");
    }

    public void eventGenerate (String pad, EventTarget et, RendererC context, String eventSpike, String eventSpikeLatch)
    {
        StringBuilder result = context.result;

        // Is delay close enough to a time-quantum?
        if (T.equals ("int"))
        {
            result.append (pad + "int step = (delay + event->dt / 2) / event->dt;\n");
            result.append (pad + "int quantizedTime = step * event->dt;\n");
            result.append (pad + "if (quantizedTime == delay)\n");  // All fractional bits are zero. Usually there are no more than 10 fractional bits (~1/1000 of a time step).
        }
        else
        {
            result.append (pad + T + " ratio = delay / event->dt;\n");
            result.append (pad + "int step = (int) round (ratio);\n");
            result.append (pad + "if (abs (ratio - step) < 1e-3)\n");
        }
        result.append (pad + "{\n");
        if (during)
        {
            result.append (pad + "  spike = new " + eventSpikeLatch + ";\n");
        }
        else
        {
            result.append (pad + "  spike = new " + eventSpike + ";\n");
        }
        if (T.equals ("int"))
        {
            result.append (pad + "  delay = quantizedTime;\n");
        }
        else
        {
            result.append (pad + "  delay = step * event->dt;\n");
        }
        result.append (pad + "}\n");
        result.append (pad + "else\n");
        result.append (pad + "{\n");
        result.append (pad + "  spike = new " + eventSpike + ";\n");
        result.append (pad + "}\n");
        result.append (pad + "spike->t = " + SIMULATOR + "currentEvent->t + delay;\n");
    }

    /**
        Emit the equations associated with a variable.
        Assumes that phase indicators have already been factored out by simplify().
    **/
    public void multiconditional (Variable v, RendererC context, String pad) throws Exception
    {
        boolean connect = context.part.getConnect ();
        boolean init    = context.part.getInit ();
        boolean isType  = v.name.equals ("$type");

        if (v.hasAttribute ("temporary")) context.result.append (pad + type (v) + " " + mangle (v) + ";\n");

        // Select the default equation
        EquationEntry defaultEquation = null;
        for (EquationEntry e : v.equations) if (e.ifString.isEmpty ()) defaultEquation = e;

        // Initialize static objects, and dump dynamic objects needed by conditions
        for (EquationEntry e : v.equations)
        {
            if (e.condition != null) prepareDynamicObjects (e.condition, context, init, pad);
        }

        // Write the conditional equations
        boolean haveIf = false;
        String padIf = pad;
        for (EquationEntry e : v.equations)
        {
            if (e == defaultEquation) continue;  // Must skip the default equation, as it will be emitted last.

            if (e.condition != null)
            {
                String ifString;
                if (haveIf)
                {
                    ifString = "else if (";
                }
                else
                {
                    ifString = "if (";
                    haveIf = true;
                    padIf = pad + "  ";
                }
                context.result.append (pad + ifString);
                e.condition.render (context);
                context.result.append (")\n");
                context.result.append (pad + "{\n");
            }
            if (isType)
            {
                // Set $type to an integer index indicating which of the splits statements in this equation set
                // was actually triggered. During finalize(), this will select a piece of code that implements
                // this particular split. Afterward, $type will be set to an appropriate index within the split,
                // per the N2A language document.
                if (! (e.expression instanceof Split))
                {
                    Backend.err.get ().println ("Unexpected expression for $type");
                    throw new Backend.AbortRun ();
                }
                int index = context.part.splits.indexOf (((Split) e.expression).parts);
                context.result.append (padIf + resolve (v.reference, context, true) + " = " + (index + 1) + ";\n");
            }
            else
            {
                prepareDynamicObjects (e.expression, context, init, pad);
                context.result.append (padIf);
                renderEquation (context, e);
            }
            if (haveIf) context.result.append (pad + "}\n");
        }

        // Write the default equation
        if (defaultEquation == null)
        {
            if (v.hasAttribute ("temporary"))
            {
                if (haveIf)
                {
                    context.result.append (pad + "else\n");
                    context.result.append (pad + "{\n");
                }
                context.result.append (padIf + zero (resolve (v.reference, context, true), v) + ";\n");
                if (haveIf) context.result.append (pad + "}\n");
            }
            else
            {
                String defaultValue = null;
                if (isType)
                {
                    defaultValue = "0";  // always reset $type to 0
                }
                else if (connect  &&  v.name.equals ("$p"))
                {
                    defaultValue = "1";
                }
                else
                {
                    // External-write variables with a combiner get reset during finalize.
                    // However, buffered variables with simple assignment (REPLACE) need
                    // to copy forward the current buffered value.
                    if (   v.assignment == Variable.REPLACE
                        && v.reference.variable.container == v.container   // local to the equation set, not a reference to an outside variable
                        && v.equations.size () > 0
                        && v.hasAny ("cycle", "externalRead")  // buffered
                        && ! v.hasAttribute ("initOnly")
                        && ! init  &&  ! connect)  // not in a phase that skips buffering
                    {
                        defaultValue = resolve (v.reference, context, false);  // copy previous value
                    }
                }

                if (defaultValue != null)
                {
                    if (haveIf)
                    {
                        context.result.append (pad + "else\n");
                        context.result.append (pad + "{\n");
                    }
                    context.result.append (padIf + resolve (v.reference, context, true) + " = " + defaultValue + ";\n");
                    if (haveIf) context.result.append (pad + "}\n");
                }
            }
        }
        else
        {
            if (haveIf)
            {
                context.result.append (pad + "else\n");
                context.result.append (pad + "{\n");
            }
            if (isType)
            {
                ArrayList<EquationSet> split = ((Split) defaultEquation.expression).parts;
                int index = context.part.splits.indexOf (split);
                context.result.append (padIf + resolve (v.reference, context, true) + " = " + (index + 1) + ";\n");
            }
            else
            {
                prepareDynamicObjects (defaultEquation.expression, context, init, pad);
                context.result.append (padIf);
                renderEquation (context, defaultEquation);
            }
            if (haveIf) context.result.append (pad + "}\n");
        }
    }

    public void renderEquation (RendererC context, EquationEntry e)
    {
        StringBuilder result = context.result;
        if (e.variable.hasAttribute ("dummy"))
        {
            e.expression.render (context);
        }
        else
        {
            String LHS = resolve (e.variable.reference, context, true);
            result.append (LHS);
            int shift = 0;
            switch (e.variable.assignment)
            {
                case Variable.REPLACE:
                    result.append (" = ");
                    break;
                case Variable.ADD:
                    result.append (" += ");
                    break;
                case Variable.MULTIPLY:
                    // raw exponent = exponentV + exponentExpression - MSB
                    // shift = raw - exponentV = expnentExpression - MSB
                    shift = e.expression.exponentNext - Operator.MSB;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        if (shift < 0) result.append (" = (int64_t) " + LHS + " * ");
                        else           result.append (" = "           + LHS + " * ");
                    }
                    else
                    {
                        result.append (" *= ");
                    }
                    break;
                case Variable.DIVIDE:
                    // raw = exponentV - exponentExpression + MSB
                    // shift = raw - exponentV = MSB - exponentExpression
                    shift = Operator.MSB - e.expression.exponentNext;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        if (shift > 0) result.append (" = ((int64_t) " + LHS + context.printShift (shift) + ") / ");
                        else           result.append (" = "            + LHS                              +  " / ");
                    }
                    else
                    {
                        result.append (" /= ");
                    }
                    break;
                case Variable.MIN:
                    result.append (" = min (" + LHS + ", ");
                    break;
                case Variable.MAX:
                    result.append (" = max (" + LHS + ", ");
            }

            boolean cli = e.variable.hasAttribute ("cli");
            if (cli) result.append ("params.get (\"" + e.variable.fullName () + "\", ");

            e.expression.render (context);

            if (cli) result.append (")");
            if (e.variable.assignment == Variable.MAX  ||  e.variable.assignment == Variable.MIN)
            {
                result.append (")");
            }
            if (shift != 0  &&  T.equals ("int"))
            {
                result.append (context.printShift (shift));
            }
        }
        result.append (";\n");
    }

    public void prepareStaticObjects (Operator op, RendererC context, String pad)
    {
        final BackendDataC bed = context.bed;

        Visitor visitor = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                for (ProvideOperator po : extensions)
                {
                    Boolean result = po.prepareStaticObjects (op, context, pad);
                    if (result != null) return result;
                }
                if (op instanceof Output)
                {
                    Output o = (Output) op;
                    if (! o.hasColumnName)  // column name is generated
                    {
                        BackendDataC bed = (BackendDataC) context.part.backendData;
                        if (context.global ? bed.needGlobalPath : bed.needLocalPath)
                        {
                            context.result.append (pad + "path (" + o.columnName + ");\n");
                            context.result.append (pad + o.columnName + " += \"." + o.variableName + "\";\n");
                        }
                        else
                        {
                            context.result.append (pad + o.columnName + " = \"" + o.variableName + "\";\n");
                        }
                    }
                    if (o.operands[0] instanceof Constant  &&  o.getKeywordFlag ("raw"))  // Apply "raw" attribute now, if set.
                    {
                        context.result.append (pad + o.name + "->raw = true;\n");
                    }
                    return true;  // Continue to drill down, because I/O functions can be nested.
                }
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    if (i.operands[0] instanceof Constant)
                    {
                        if (i.usesTime ()  &&  ! context.global  &&  ! T.equals ("int"))  // Note: In the case of T==int, we don't need to set epsilon because it is already set to 1 by the constructor.
                        {
                            // TODO: This is a bad way to set time epsilon, but not sure if there is a better one.
                            // The main problem is that several different instances may all do the same initialization,
                            // and they may disagree on epsilon, perhaps even by several orders of magnitude.
                            // We could make a compile-time estimate of the smallest dt, and use dt/1000 everywhere.
                            // This is similar to the current approach for estimating time exponent for fixed-point.

                            // Read $t' as an lvalue, to ensure we get any newly-set frequency.
                            // However, can't do this if $t' is a constant. In that case, no variable exists.
                            boolean lvalue = ! bed.dt.hasAttribute ("constant");
                            context.result.append (pad + i.name + "->epsilon = " + resolve (bed.dt.reference, context, lvalue) + " / 1000.0");
                            if (T.equals ("float")) context.result.append ("f");
                            context.result.append (";\n");
                        }
                    }
                    return true;
                }
                return true;
            }
        };
        op.visit (visitor);
    }

    /**
        Build complex sub-expressions into a single local variable that can be referenced by the equation.
    **/
    public void prepareDynamicObjects (Operator op, RendererC context, boolean init, String pad)
    {
        final BackendDataC bed = context.bed;

        // Pass 1 -- Strings and matrix expressions
        Visitor visitor1 = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof BuildMatrix)
                {
                    BuildMatrix m = (BuildMatrix) op;
                    int rows = m.getRows ();
                    int cols = m.getColumns ();
                    context.result.append (pad + "MatrixFixed<" + T + "," + rows + "," + cols + "> " + m.name + ";\n");
                    for (int r = 0; r < rows; r++)
                    {
                        if (cols == 1)
                        {
                            context.result.append (pad + m.name + "[" + r + "] = ");
                            m.operands[0][r].render (context);
                            context.result.append (";\n");
                        }
                        else
                        {
                            for (int c = 0; c < cols; c++)
                            {
                                context.result.append (pad + m.name + "(" + r + "," + c + ") = ");
                                m.operands[c][r].render (context);
                                context.result.append (";\n");
                            }
                        }
                    }
                    return false;
                }
                if (op instanceof Add)
                {
                    Add a = (Add) op;
                    if (a.name != null)
                    {
                        context.result.append (pad + "String " + a.name + ";\n");
                        for (Operator o : flattenAdd (a))
                        {
                            context.result.append (pad + a.name + " += ");
                            o.render (context);
                            context.result.append (";\n");
                        }
                        return false;
                    }
                }
                return true;
            }
        };
        op.visit (visitor1);

        // Pass 2 -- I/O functions
        Visitor visitor2 = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof ReadMatrix)
                {
                    ReadMatrix r = (ReadMatrix) op;
                    if (! (r.operands[0] instanceof Constant))
                    {
                        context.result.append (pad + "MatrixInput<" + T + "> * " + r.name + " = matrixHelper<" + T + "> (" + r.fileName);
                        if (T.equals ("int")) context.result.append (", " + r.exponent);
                        context.result.append (");\n");
                    }
                    return false;
                }
                if (op instanceof Mfile)
                {
                    Mfile m = (Mfile) op;
                    if (! (m.operands[0] instanceof Constant))
                    {
                        context.result.append (pad + "Mfile<" + T + "> * " + m.name + " = MfileHelper<" + T + "> (" + m.fileName + ");\n");
                    }
                    return false;
                }
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    if (! (i.operands[0] instanceof Constant))
                    {
                        context.result.append (pad + "InputHolder<" + T + "> * " + i.name + " = inputHelper<" + T + "> (" + i.fileName);
                        if (T.equals ("int")) context.result.append (", " + i.exponent);
                        context.result.append (");\n");

                        boolean smooth =             i.getKeywordFlag ("smooth");
                        boolean time   = smooth  ||  i.getKeywordFlag ("time");
                        if (time)
                        {
                            if (time)   context.result.append (pad + i.name + "->time = true;\n");
                            if (smooth) context.result.append (pad + i.name + "->smooth = true;\n");
                            if (! context.global  &&  ! T.equals ("int"))
                            {
                                boolean lvalue = ! bed.dt.hasAttribute ("constant");
                                context.result.append (pad + i.name + "->epsilon = " + resolve (bed.dt.reference, context, lvalue) + " / 1000.0");
                                if (T.equals ("float")) context.result.append ("f");
                                context.result.append (";\n");
                            }
                        }
                    }
                    return true;  // I/O functions can be nested
                }
                if (op instanceof Output)
                {
                    Output o = (Output) op;
                    if (! (o.operands[0] instanceof Constant))
                    {
                        context.result.append (pad + "OutputHolder<" + T + "> * " + o.name + " = outputHelper<" + T + "> (" + o.fileName + ");\n");
                        if (o.getKeywordFlag ("raw"))
                        {
                            context.result.append (pad + o.name + "->raw = true;\n");
                        }
                    }
                    return true;
                }
                return true;
            }
        };
        op.visit (visitor2);
    }

    public List<Operator> flattenAdd (Add add)
    {
        ArrayList<Operator> result = new ArrayList<Operator> ();
        if (add.operand0 instanceof Add) result.addAll (flattenAdd ((Add) add.operand0));
        else                             result.add (add.operand0);
        if (add.operand1 instanceof Add) result.addAll (flattenAdd ((Add) add.operand1));
        else                             result.add (add.operand1);
        return result;
    }

    public String mangle (Variable v)
    {
        return mangle (v.nameString ());
    }

    public String mangle (String prefix, Variable v)
    {
        return mangle (prefix, v.nameString ());
    }

    public String mangle (String input)
    {
        return mangle ("_", input);
    }

    /**
        Converts identifiers into a form that can be compiled.
        Legitimate identifiers in our language follow essentially the same
        rules as Java or C++, with the addition of three characters:
        space, period and single-quote. Here we assume that Java identifier
        functions also satisfy C++ rules, and simply replace our additional
        characters with underscores. There are some degenerate cases where
        this won't produce a unique identifier. Examples:
            A.B and A_B are both in the model
            A' and A_ are both in the model
        These are very unlikely in practice, and ignoring them will make more
        readable code.
    **/
    public String mangle (String prefix, String input)
    {
        // Use filter from NodePart.
        // NodePart allows spaces in names, so we have to do one extra step for C++.
        if (supportsUnicodeIdentifiers) return prefix + NodePart.validIdentifierFrom (input).replaceAll (" ", "_");

        // Old-school mangling
        // Just like the above method, this is not guaranteed to create unique names,
        // but the failure case are rather pathological.
        StringBuilder result = new StringBuilder (prefix);
        for (char c : input.toCharArray ())
        {
            // Even though underscore (_) is a legitimate character,
            // we don't use it.  Instead it is used as an escape for unicode.
            // We use variable length unicode values because there is no need to parse
            // the identifiers back into wide characters.
            if (   ('a' <= c && c <= 'z')
                || ('A' <= c && c <= 'Z')
                || ('0' <= c && c <= '9'))
            {
                result.append (c);
            }
            else
            {
                result.append ("_" + Integer.toHexString (c));
            }
        }
        return result.toString ();
    }

    public String type (Variable v)
    {
        if (v.type instanceof Matrix)
        {
            if (v.hasAttribute ("MatrixPointer")) return "MatrixAbstract<" + T + "> *";
            Matrix m = (Matrix) v.type;
            int rows = m.rows ();
            int cols = m.columns ();
            if (rows > 0  &&  cols > 0) return "MatrixFixed<" + T + "," + rows + "," + cols + ">";  // Known dimension, so use more efficient storage.
            return "Matrix<" + T + ">";  // Arbitrary dimension
        }
        if (v.type instanceof Text) return "String";
        return T;
    }

    public static String zero (String name, Variable v) throws Exception
    {
        if (v.type instanceof Scalar) return name + " = 0";
        if (v.type instanceof Matrix) return "::clear (" + name + ")";  // Don't check for matrix pointer, because zero() should never be called for such variables.
        if (v.type instanceof Text  ) return name + ".clear ()";
        Backend.err.get ().println ("Unknown Type");
        throw new Backend.AbortRun ();
    }

    public static String clear (String name, Variable v, double value, RendererC context) throws Exception
    {
        String p = context.print (value, v.exponent);
        if (v.type instanceof Scalar) return name + " = " + p;
        if (v.type instanceof Matrix) return "::clear (" + name + ", " + p + ")";  // Don't check for matrix pointer, because clear() should never be called for such variables.
        if (v.type instanceof Text  ) return name + ".clear ()";
        Backend.err.get ().println ("Unknown Type");
        throw new Backend.AbortRun ();
    }

    public static String clearAccumulator (String name, Variable v, RendererC context) throws Exception
    {
        switch (v.assignment)
        {
            case Variable.MULTIPLY:
            case Variable.DIVIDE:   return clear (name, v, 1,                        context);
            case Variable.MIN:      return clear (name, v, Double.POSITIVE_INFINITY, context);
            case Variable.MAX:      return clear (name, v, Double.NEGATIVE_INFINITY, context);
            case Variable.ADD:
            default:                return zero (name, v);
        }
    }

    public String prefix (EquationSet t)
    {
        if (t == null) return "Wrapper";
        String result = mangle (t.name);
        while (t != null)
        {
            t = t.container;
            if (t != null) result = mangle (t.name) + "_" + result;
        }
        return result;
    }

    public String resolve (VariableReference r, RendererC context, boolean lvalue)
    {
        return resolve (r, context, lvalue, "", false);
    }

    /**
        @param v A variable to convert into C++ code that can access it at runtime.
        @param context For the AST rendering system.
        @param lvalue Indicates that this will receive a value assignment. The other case is an rvalue, which will simply be read.
        @param base Injects a pointer at the beginning of the resolution path.
        @param logical The intended use is in a boolean expression, such as an if-test.
    **/
    public String resolve (VariableReference r, RendererC context, boolean lvalue, String base, boolean logical)
    {
        if (r == null  ||  r.variable == null) return "unresolved";

        // Because $live has some rather complex access rules, take special care to ensure
        // that it always returns false when either $connect or $init are true.
        if (r.variable.name.equals ("$live")  &&  r.variable.container == context.part)
        {
            if (context.part.getConnect ()  ||  context.part.getInit ()) return "0";
        }

        if (r.variable.hasAttribute ("constant")  &&  ! lvalue)  // A constant will always be an rvalue, unless it is being loaded into a local variable (special case for $t').
        {
            EquationEntry e = r.variable.equations.first ();
            StringBuilder temp = context.result;
            StringBuilder result = new StringBuilder ();
            context.result = result;
            e.expression.render (context);
            context.result = temp;
            return result.toString ();
        }

        String containers = resolveContainer (r, context, base);

        if (r.variable.hasAttribute ("instance"))
        {
            return stripDereference (containers);
        }

        String name = "";
        BackendDataC bed = (BackendDataC) r.variable.container.backendData;  // NOT context.bed !
        if (r.variable.hasAttribute ("preexistent"))
        {
            String vname = r.variable.name;
            if (vname.startsWith ("$"))
            {
                int vorder = r.variable.order;
                if (vname.equals ("$t"))
                {
                    if (! lvalue)
                    {
                        if      (vorder == 0) name = SIMULATOR + "currentEvent->t";
                        else if (vorder == 1)
                        {
                            if (context.hasEvent) name = "event->dt";
                            else                  name = "getEvent ()->dt";
                        }
                        // Higher orders of $t should not be "preexistent". They are handled by the main case below.
                    }
                    // for lvalue, fall through to the main case below
                }
                else if (vname.equals ("$n"))
                {
                    if (! lvalue  &&  vorder == 0)
                    {
                        name = "n";
                    }
                }
            }
            else
            {
                return vname;  // most likely a local variable, for example "rc" in mapIndex()
            }
        }
        if (r.variable.name.equals ("$live"))
        {
            if (r.variable.hasAttribute ("accessor"))
            {
                if (lvalue) return "unresolved";
                name = "getLive ()";
            }
            else  // not "constant" or "accessor", so must be direct access
            {
                if (logical) return "(" + containers + "flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ")";
                else return "((" + containers + "flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ") ? 1 : 0)";
            }
        }
        else if (r.variable.hasAttribute ("accessor"))
        {
            return "unresolved";  // At present, only $live can have "accessor" attribute.
        }
        if (r.variable.name.endsWith (".$count"))
        {
            if (lvalue) return "unresolved";
            String alias = r.variable.name.substring (0, r.variable.name.lastIndexOf ("."));
            name = mangle (alias) + "->" + prefix (r.variable.container) + "_" + mangle (alias) + "_count";
        }
        if (name.length () == 0)
        {
            // Write to buffered value, except during init phase.
            if (lvalue  &&  ! context.part.getInit ()  &&  (bed.globalBuffered.contains (r.variable)  ||  bed.localBuffered.contains (r.variable)))
            {
                name = mangle ("next_", r.variable);
            }
            else
            {
                name = mangle (r.variable);
            }
        }
        if (r.variable.hasAttribute ("MatrixPointer")  &&  ! lvalue)
        {
            return "(* " + containers + name + ")";  // Actually stored as a pointer
        }
        return containers + name;
    }

    /**
        Compute a series of pointers to get from current part to r.
        Result does not include the variable name itself.
    **/
    public String resolveContainer (VariableReference r, RendererC context, String base)
    {
        String containers = base;
        EquationSet current = context.part;
        boolean global = context.global;
        int last = r.resolution.size () - 1;
        for (int i = 0; i <= last; i++)
        {
            Object o = r.resolution.get (i);
            if (o instanceof EquationSet)  // We are following the containment hierarchy.
            {
                EquationSet s = (EquationSet) o;
                if (s.container == current)  // descend into one of our contained populations
                {
                    if (i == last  &&  r.variable.hasAttribute ("global"))  // descend to the population object
                    {
                        // No need to cast the population instance, because it is explicitly typed
                        containers += mangle (s.name) + ".";
                        global = true;
                    }
                    else  // descend to a singleton instance of the population.
                    {
                        BackendDataC bed = (BackendDataC) s.backendData;
                        if (! bed.singleton)
                        {
                            Backend.err.get ().println ("ERROR: Down-reference to population with more than one instance is ambiguous.");
                            throw new AbortRun ();
                        }
                        containers += mangle (s.name) + ".instance.";
                        global = false;
                    }
                }
                else  // ascend to our container
                {
                    containers = containerOf (current, i == 0  &&  context.global, containers);
                    global = false;
                }
                current = s;
            }
            else if (o instanceof ConnectionBinding)  // We are following a part reference (which means we are a connection)
            {
                ConnectionBinding c = (ConnectionBinding) o;
                containers += mangle (c.alias) + "->";
                current = c.endpoint;
                global = false;
            }
        }

        if (r.variable.hasAttribute ("global")  &&  ! global)
        {
            // Must ascend to our container and then descend to our population object.
            containers = containerOf (current, false, containers);
            containers += mangle (current.name) + ".";
        }

        return containers;
    }

    /**
        We either have direct access to our container, or we are a connection using indirect access through an endpoint.
        @param global Indicates that the current context is a population class. Because Population::container
        is declared as a generic part in runtime.h, access requires a typecast.
    **/
    public String containerOf (EquationSet s, boolean global, String base)
    {
        BackendDataC bed = (BackendDataC) s.backendData;
        if (bed.pathToContainer != null  &&  ! global) base += mangle (bed.pathToContainer) + "->";
        base += "container";
        if (global) return "((" + prefix (s.container) + " *) " + base + ")->";
        return base + "->";
    }

    public String stripDereference (String containers)
    {
        if (containers.endsWith ("->")) return containers.substring (0, containers.length () - 2);
        if (containers.endsWith ("." )) return containers.substring (0, containers.length () - 1);
        return containers;
    }

    /**
        Generate code to enumerate all instances of a connection endpoint. Handles deep hierarchical
        embedding.

        <p>A connection resolution can take 3 kinds of step:
        <ul>
        <li>Up to container
        <li>Down to a population
        <li>Through another connection
        </ul>

        @param current EquationSet associated with the context of the current step of resolution.
        @param pointer Name of a pointer to the context for the current step of resolution. Can
        be a chain of pointers. Can be empty if the code is to be emitted in the current context.
        @param depth Position in the resolution array of our next step.
        @param prefix Spaces to insert in front of each line to maintain nice indenting.
    **/
    public void assembleInstances (EquationSet current, String pointer, List<Object> resolution, int depth, String prefix, StringBuilder result)
    {
        int last = resolution.size () - 1;
        for (int i = depth; i <= last; i++)
        {
            Object r = resolution.get (i);
            if (r instanceof EquationSet)
            {
                EquationSet s = (EquationSet) r;
                if (r == current.container)  // ascend to parent
                {
                    pointer = containerOf (current, i == 0, pointer);
                }
                else  // descend to child
                {
                    pointer += mangle (s.name) + ".";
                    if (i < last)  // Enumerate the instances of child population.
                    {
                        if (depth == 0)
                        {
                            result.append (prefix + "result->instances = new vector<Part<" + T + "> *>;\n");
                            result.append (prefix + "result->deleteInstances = true;\n");
                        }
                        String it = "it" + i;
                        result.append (prefix + "for (auto " + it + " : " + pointer + "instances)\n");
                        result.append (prefix + "{\n");
                        assembleInstances (s, it + "->", resolution, i+1, prefix + "  ", result);
                        result.append (prefix + "}\n");
                        return;
                    }
                }
                current = s;
            }
            else if (r instanceof ConnectionBinding)
            {
                ConnectionBinding c = (ConnectionBinding) r;
                pointer += mangle (c.alias) + "->";
                current = c.endpoint;
            }
            // else something is broken. This case should never occur.
        }

        // "pointer" now references the target population.
        // Collect its instances.
        BackendDataC bed = (BackendDataC) current.backendData;
        if (bed.singleton)
        {
            result.append (prefix + "bool newborn = " + pointer + "instance.flags & (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
            if (depth == 0)
            {
                result.append (prefix + "result->instances = new vector<Part<" + T + "> *>;\n");
                result.append (prefix + "result->deleteInstances = true;\n");
            }
            result.append (prefix + "if (result->firstborn == INT_MAX  &&  newborn) result->firstborn = result->instances->size ();\n");
            result.append (prefix + "result->instances->push_back (& " + pointer + "instance);\n");
        }
        else
        {
            if (depth == 0)  // No enumerations occurred during the resolution, so no list was created.
            {
                // Simply reference the existing list of instances.
                result.append (prefix + "result->firstborn = " + pointer + "firstborn;\n");
                result.append (prefix + "result->instances = (vector<Part<" + T + "> *> *) & " + pointer + "instances;\n");
            }
            else  // Enumerations occurred, so we are already accumulating a list.
            {
                // Append instances to accumulating list.
                result.append (prefix + "if (result->firstborn == INT_MAX  &&  " + pointer + "firstborn < " + pointer + "instances.size ()) result->firstborn = result->instances->size () + " + pointer + "firstborn;\n");
                result.append (prefix + "result->instances->insert (result->instances->end (), " + pointer + "instances.begin (), " + pointer + "instances.end ());\n");
            }
        }

        // Schedule the population to have its newborn flags cleared.
        // We assume that any newborn flags along the path to this population are either unimportant
        // or will get cleared elsewhere.
        result.append (prefix + "if (! (" + pointer + "flags & (" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + "))\n");
        result.append (prefix + "{\n");
        result.append (prefix + "  " + pointer + "flags |= (" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + ";\n");
        pointer = stripDereference (pointer);
        if (pointer.isEmpty ()) pointer = "this";
        else                    pointer = "& " + pointer;
        result.append (prefix + "  " + SIMULATOR + "clearNew (" + pointer + ");\n");
        result.append (prefix + "}\n");
    }
}
