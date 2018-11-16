/*
Copyright 2016-2018 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.db;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
    A top-level node which maps to a directory on the file system.
    Each child node maps to a file under this directory. However, the document file need not
    be a direct child of this directory. Instead, some additional pathing may be added.
    This allows the direct children of this directory to be subdirectories, and each document
    file may be a specifically-named entry in a subdirectory.
**/
public class MDir extends MNode
{
    protected String  name;    // MDirs could be held in a collection, so this provides a way to reference them.
	protected Path    root;    // The directory containing the files or subdirs that constitute the children of this node
	protected String  suffix;  // Relative path to document file, or null if documents are directly under root
	protected boolean loaded;  // Indicates that an initial read of the dir has been done. After that, it is not necessary to monitor the dir, only keep track of documents internally.

	protected NavigableMap<String,SoftReference<MDoc>> children = new TreeMap<String,SoftReference<MDoc>> ();
	protected Set<MDoc> writeQueue = new HashSet<MDoc> ();  // By storing strong references to docs that need to be saved, we prevent them from being garbage collected until that is done.

    protected List<ChangeListener> listeners = new ArrayList<ChangeListener> ();

    /**
        Adds a listener that will be notified whenever a change occurs to our collection of children.
    **/
    public synchronized void addChangeListener (ChangeListener listener)
    {
        listeners.add (listener);
    }

    public synchronized void fireChanged ()
    {
        if (listeners.size () > 0)
        {
            ChangeEvent e = new ChangeEvent (this);
            for (ChangeListener c : listeners) c.stateChanged (e);
        }
    }

    public MDir (Path root)
    {
        this (null, root, null);
    }

	public MDir (Path root, String suffix)
	{
	    this (null, root, suffix);
	}

    public MDir (String name, Path root, String suffix)
    {
        this.name = name;
        this.root = root;
        this.suffix = suffix;
        try {Files.createDirectories (root);}  // We take the liberty of forcing the dir to exist.
        catch (IOException e) {}
    }

	public String key ()
	{
	    if (name == null) return root.toString ();
	    return name;
	}

	public String getOrDefault (String defaultValue)
	{
	    if (root == null) return defaultValue;  // This should never happen.
	    return root.toAbsolutePath ().toString ();
	}

	public Path pathForChild (String index)
	{
	    Path result = root.resolve (index);
        if (suffix != null  &&  ! suffix.isEmpty ()) result = result.resolve (suffix);
        return result;
	}

	public static String validFilenameFrom (String name)
	{
	    // TODO: This is only sufficient for Linux. What else is needed for Windows?
        name = name.replace ("\\", "-");
        name = name.replace ("/", "-");
        return name;
	}

	public synchronized MNode child (String index)
    {
	    if (index.isEmpty ()) return null;  // The file-existence code below can be fooled by an empty string, so explicitly guard against it.
	    MDoc result = null;
	    SoftReference<MDoc> reference = children.get (index);
	    if (reference != null) result = reference.get ();
	    if (result == null)  // We have never loaded this document, or it has been garbage collected.
	    {
	        if (! Files.isReadable (pathForChild (index))) return null;
	        result = new MDoc (this, index);
	        children.put (index, new SoftReference<MDoc> (result));
	    }
        return result;
    }

	/**
	    Empty this directory of all files.
	    This is an extremely dangerous function! It destroys all data in the directory on disk and all data pending in memory.
	**/
    public synchronized void clear ()
    {
        children.clear ();
        writeQueue.clear ();
        try
        {
            Files.walkFileTree (root.toAbsolutePath (), new SimpleFileVisitor<Path> ()
            {
                public FileVisitResult visitFile (final Path file, final BasicFileAttributes attrs) throws IOException
                {
                    Files.delete (file);
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult postVisitDirectory (final Path dir, final IOException e) throws IOException
                {
                    if (! dir.equals (root.toAbsolutePath ())) Files.delete (dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException e)
        {
        }

        fireChanged ();
    }

    public synchronized void clear (String index)
    {
        SoftReference<MDoc> ref = children.remove (index);
        if (ref != null) writeQueue.remove (ref.get ());
        try {Files.delete (pathForChild (index));}
        catch (IOException e) {}

        fireChanged ();
    }

    public synchronized int size ()
	{
        String[] list = root.toFile ().list ();
        if (writeQueue.isEmpty ()) return list.length;

        Set<String> files = new HashSet<String> (Arrays.asList (list));
        for (MDoc doc : writeQueue) files.add (doc.get ());
        return files.size ();
	}

    /**
        Creates a new MDoc in this directory, or renames it if it already exists.
        The reason setting the value moves an MDoc is that MDoc treats its value as its file name (in addition
        to the index, which is also its file name). These semantics are arbitrary, but they create a 
        convenient way to specify a file rename without adding another function. We simply force everything to
        be consistent with the newly-set value. In the case of a new file, the index takes precedence, so the
        value is ignored. It is sufficient to pass value="" to create a new file.
        
        @param index The file name for a new document, or the source file name for a move.
        @param value The destination file name for a move. Ignored if a file named by the given index does not exist.
     */
    public synchronized MNode set (String index, String value)
    {
        MDoc result = (MDoc) child (index);
        if (result == null)  // new document
        {
            result = new MDoc (this, index);
            children.put (index, new SoftReference<MDoc> (result));

            // Set the new document to save.
            // Due to subtle interactions with MDoc.save() and load(), this will not actually force an empty MDoc to exist.
            // Specifically, load() on a non-existent file with clear the needsWrite flag. The load() call is forced by
            // the save() call in order to iterate of children. If, OTOH, children are added and removed before save(),
            // the empty MDoc will still be flagged to write. Whether this is a bug or a feature is debatable. The
            // net effect for the N2A application is that new models that are not actually filled in will evaporate.
            result.markChanged ();
        }
        else  // existing document; move if needed
        {
            if (value == null  ||  value.isEmpty ()  ||  value.equals (index)) return result;
            result.set (value);  // MDoc does all the low-level work, including updating our children collection with the new index
            // Note: We don't need to mark this document to be saved, since we are moving an existing file on disk.
            // However, if the document currently has unsaved changes, they will eventually get written to the new location.
        }
        fireChanged ();
        return result;
    }

    /**
        Renames an MDoc on disk.
        If you already hold a reference to the MDoc named by fromIndex, then that reference remains valid
        after the move.
    **/
    public synchronized void move (String fromIndex, String toIndex)
    {
        if (toIndex.equals (fromIndex)) return;
        save ();  // If this turns out to be too much work, then scan the write queue for fromIndex and save it directly.

        // This operation is independent of bookkeeping in children
        Path fromPath = pathForChild (fromIndex).toAbsolutePath ();
        Path toPath   = pathForChild (toIndex  ).toAbsolutePath ();
        try
        {
            Files.deleteIfExists (toPath);
            Files.move (fromPath, toPath, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            // This can happen if a new doc has not yet been flushed to disk.
        }

        MDoc source = null;
        SoftReference<MDoc> ref = children.get (fromIndex);
        if (ref != null) source = ref.get ();
        children.remove (toIndex);
        children.remove (fromIndex);
        if (source != null)
        {
            source.name = toIndex;
            children.put (toIndex, new SoftReference<MDoc> (source));
        }

        fireChanged ();
    }

    public class IteratorWrapperSoft implements Iterator<MNode>
    {
        List<String>     keys;
        Iterator<String> iterator;
        String           key;  // of the most recent node returned by next()

        public IteratorWrapperSoft (List<String> keys)
        {
            this.keys = keys;
            iterator = keys.iterator ();
        }

        public boolean hasNext ()
        {
            return iterator.hasNext ();
        }

        /**
            If a document is deleted while the iterator is running, this could return null.
            If a document is added, it will not be included.
        **/
        public MNode next ()
        {
            key = iterator.next ();
            return child (key);
        }

        public void remove ()
        {
            clear (key);
            iterator.remove ();
        }
    }

    public synchronized Iterator<MNode> iterator ()
    {
        load ();
        return new IteratorWrapperSoft (new ArrayList<String> (children.keySet ()));  // Duplicate the keys, to avoid concurrent modification
    }

    public synchronized void load ()
    {
        if (loaded) return;

        NavigableMap<String,SoftReference<MDoc>> newChildren = new TreeMap<String,SoftReference<MDoc>> ();
        for (String index : root.toFile ().list ())  // This may cost a lot of time in some cases. However, N2A should never have more than about 10,000 models in a dir.
        {
            if (index.startsWith (".")) continue; // Filter out special files. This allows, for example, a git repo to share the models dir.
            newChildren.put (index, null);
        }
        for (Entry<String,SoftReference<MDoc>> c : children.entrySet ())
        {
            newChildren.put (c.getKey (), c.getValue ());
        }
        children = newChildren;

        loaded = true;
    }

    public synchronized void save ()
    {
        for (MDoc doc: writeQueue) doc.save ();
        writeQueue.clear ();  // This releases the strong references, so these docs can be garbage collected if needed.
    }
}
