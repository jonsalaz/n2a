/*
Copyright 2017,2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.util.List;

import javax.swing.undo.UndoableEdit;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodePart;

public class DeletePart extends UndoableView
{
    protected List<String> path;  // to containing part
    protected int          index; // where to insert among siblings
    protected boolean      canceled;
    protected String       name;
    protected MNode        savedSubtree;
    protected boolean      neutralized;

    public DeletePart (NodePart node, boolean canceled)
    {
        // Never delete a part in parent position, because after that we would need to drill up anyway.
        if (view.asParent)
        {
            view.asParent = false;
            view.first    = false;  // Force view to change on first call to redo(), since at this point the part to be deleted is still in parent position.
        }

        NodeBase container = (NodeBase) node.getTrueParent ();
        path          = container.getKeyPath ();
        index         = container.getIndex (node);
        this.canceled = canceled;
        name          = node.source.key ();

        savedSubtree = new MVolatile ();
        savedSubtree.merge (node.source.getSource ());  // Only take the top-doc data, not the collated tree.
    }

    public void undo ()
    {
        super.undo ();
        AddPart.create (path, index, name, savedSubtree, false);
    }

    public void redo ()
    {
        super.redo ();
        AddPart.destroy (path, canceled, name);
    }

    public boolean replaceEdit (UndoableEdit edit)
    {
        if (edit instanceof AddPart)
        {
            AddPart ap = (AddPart) edit;
            if (path.equals (ap.path)  &&  name.equals (ap.name)  &&  ap.nameIsGenerated)
            {
                neutralized = true;
                return true;
            }
        }
        return false;
    }

    public boolean anihilate ()
    {
        return neutralized;
    }
}
