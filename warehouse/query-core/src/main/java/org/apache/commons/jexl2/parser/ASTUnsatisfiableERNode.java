package org.apache.commons.jexl2.parser;

/**
 * An ASTERNode which cannot be run against the index. Typically, this signifies that we *could* have run the query but chose not to for any number of reasons
 *
 * 1. Regular expression we don't support (".*", ".*foo.*") 2. Non-indexed 3. Expansion of the regex exceeded configured limits
 */
public class ASTUnsatisfiableERNode extends ASTERNode {
    public ASTUnsatisfiableERNode(int id) {
        super(id);
    }

    public ASTUnsatisfiableERNode(Parser p, int id) {
        super(p, id);
    }

    /** Accept the visitor. **/
    @Override
    public Object jjtAccept(ParserVisitor visitor, Object data) {
        return visitor.visit(this, data);
    }

    public static ASTUnsatisfiableERNode create() {
        return new ASTUnsatisfiableERNode(ParserTreeConstants.JJTERNODE);
    }
}
