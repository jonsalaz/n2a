/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.language;

import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.language.parse.ASTListNode;
import gov.sandia.n2a.language.parse.ASTNodeBase;
import gov.sandia.n2a.language.parse.ParseException;

public class Function extends Operator
{
    public Operator[] operands;

    public void getOperandsFrom (ASTNodeBase node) throws ParseException
    {
        if (node.jjtGetNumChildren () != 1) throw new ParseException ("AST for function has unexpected form");
        Object o = node.jjtGetChild (0);
        if (! (o instanceof ASTListNode)) throw new ParseException ("AST for function has unexpected form");
        ASTListNode l = (ASTListNode) o;
        int count = l.jjtGetNumChildren ();
        operands = new Operator[count];
        for (int i = 0; i < count; i++) operands[i] = Operator.getFrom ((ASTNodeBase) l.jjtGetChild (i));
    }

    public boolean isOutput ()
    {
        for (int i = 0; i < operands.length; i++)
        {
            if (operands[i].isOutput ()) return true;
        }
        return false;
    }

    public boolean isInitOnly ()
    {
        for (int i = 0; i < operands.length; i++)
        {
            if (! operands[i].isInitOnly ()) return false;
        }
        // A function with no operands is "initOnly"
        return true;
    }

    public void visit (Visitor visitor)
    {
        if (! visitor.visit (this)) return;
        for (int i = 0; i < operands.length; i++) operands[i].visit (visitor);
    }

    public Operator transform (Transformer transformer)
    {
        Operator result = transformer.transform (this);
        if (result != null) return result;
        for (int i = 0; i < operands.length; i++) operands[i] = operands[i].transform (transformer);
        return this;
    }

    public Operator simplify (Variable from)
    {
        for (int i = 0; i < operands.length; i++) operands[i] = operands[i].simplify (from);
        return this;
    }

    public void render (Renderer renderer)
    {
        if (renderer.render (this)) return;
        renderer.result.append (toString () + "(");
        for (int a = 0; a < operands.length; a++)
        {
            operands[a].render (renderer);
            if (a < operands.length - 1) renderer.result.append (", ");
        }
        renderer.result.append (")");
    }
}
