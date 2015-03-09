/*
 * Copyright (C) 2015 Tim Vaughan (tgvaughan@gmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacter.operators.unrestricted;

import bacter.Conversion;
import bacter.ConversionGraph;
import beast.core.parameter.RealParameter;
import beast.evolution.tree.Node;
import beast.evolution.tree.coalescent.ConstantPopulation;
import beast.util.Randomizer;

/**
 * Operator which reversibly deletes a conversion, modifying the CF
 * to match the marginal tree of the original conversion.
 *
 * @author Tim Vaughan (tgvaughan@gmail.com)
 */
public class ClonalFrameConversionSwap extends ConversionCreationOperator {

    @Override
    public double proposal() {
        if (Randomizer.nextBoolean())
            return deleteConversion();
        else
            return createConversion();
    }

    public double deleteConversion() {
        double logHGF = 0.0;

        if (acg.getConvCount()==0)
            return Double.NEGATIVE_INFINITY;

        Conversion conv = acg.getConversions().get(
            Randomizer.nextInt(acg.getConvCount()));

        // Skip invisible conversions:
        if (conv.getNode1() == conv.getNode2())
            return Double.NEGATIVE_INFINITY;

        logHGF -= Math.log(1.0/acg.getConvCount());

        // Abort if conversions attach to node1 above height1.
        for (Conversion otherConv : acg.getConversions()) {
            if (otherConv == conv)
                continue;

            if ((otherConv.getNode1() == conv.getNode1()
                && otherConv.getHeight1() > conv.getHeight1())
                || (otherConv.getNode2() == conv.getNode1()
                && otherConv.getHeight2()> conv.getHeight2()))
                return Double.NEGATIVE_INFINITY;
        }

        // Move all conversion attachments from parent to sister.
        Node parent = conv.getNode1().getParent();
        Node sister = getSibling(conv.getNode1());

        for (Conversion otherConv : acg.getConversions()) {
            if (otherConv == conv)
                continue;

            if (otherConv.getNode1() == parent)
                otherConv.setNode1(sister);

            if (otherConv.getNode2() == parent)
                otherConv.setNode2(sister);
        }

        // Detach node1 from parent:
        parent.removeChild(sister);
        if (parent.getParent() != null) {
            Node grandParent = parent.getParent();
            grandParent.removeChild(parent);
            grandParent.addChild(sister);
        } else
            sister.setParent(null);

        if (conv.getNode2() == parent)
            conv.setNode2(sister);

        // Swap heights of parent and height2
        double oldParentHeight = parent.getHeight();
        parent.setHeight(conv.getHeight2());
        conv.setHeight2(oldParentHeight);

        // Move conversions from node2 to parent

        for (Conversion otherConv : acg.getConversions()) {
            if (otherConv == conv)
                continue;

            if (otherConv.getNode1() == conv.getNode2()
                && otherConv.getHeight1() > parent.getHeight())
                otherConv.setNode1(parent);

            if (otherConv.getNode2() == conv.getNode2()
                && otherConv.getHeight2() > parent.getHeight())
                otherConv.setNode2(parent);
        }

        // Make final topology changes:
        Node oldParent = conv.getNode2().getParent();
        parent.addChild(conv.getNode2());
        if (oldParent != null) {
            oldParent.removeChild(conv.getNode2());
            oldParent.addChild(parent);
        }

        logHGF += getAffectedRegionProb(conv) + getEdgeAttachmentProb(conv);

        // Remove conversion
        acg.deleteConversion(conv);

        return logHGF;
    }

    public double createConversion() {
        double logHGF = 0.0;

        Conversion newConv = new Conversion();

        // Choose affected sites:
        logHGF -= drawAffectedRegion(newConv);
        
        // Choose attchment points:
        logHGF -= attachEdge(newConv);

        // Skip invisible conversions:
        if (newConv.getNode1() == newConv.getNode2())
            return Double.NEGATIVE_INFINITY;

        // Check for conversions which attach above chosen point
        for (Conversion conv : acg.getConversions()) {
            if ((conv.getNode1() == newConv.getNode1()
                && conv.getHeight1()>newConv.getHeight1())
                || (conv.getNode2() == newConv.getNode1()
                && conv.getHeight2()>newConv.getHeight1()))
                return Double.NEGATIVE_INFINITY;
        }

        Node parent = newConv.getNode1().getParent();
        Node sister = getSibling(newConv.getNode1());
        double newHeight2 = parent.getHeight();

        for (Conversion conv : acg.getConversions()) {
            if (conv.getNode1() == parent)
                conv.setNode1(sister);

            if (conv.getNode2() == parent)
                conv.setNode2(sister);
        }

        parent.removeChild(sister);
        if (parent.isRoot()) {
            sister.setParent(null);
        } else {
            Node grandParent = parent.getParent();
            grandParent.removeChild(parent);
            grandParent.addChild(sister);
        }

        if (newConv.getNode2() == parent)
            newConv.setNode2(sister);

        parent.setHeight(newConv.getHeight2());
        newConv.setHeight2(newHeight2);

        for (Conversion conv : acg.getConversions()) {
            if ((conv.getNode1() == newConv.getNode2())
                && (conv.getHeight1()>parent.getHeight()))
                conv.setNode1(parent);

            if ((conv.getNode2() == newConv.getNode2())
                && (conv.getHeight2()>parent.getHeight()))
                conv.setNode2(parent);
        }

        if (newConv.getNode2().isRoot()) {
            parent.setParent(null);
            acg.setRoot(parent);
            parent.addChild(newConv.getNode2());
        } else {
            Node grandParent = newConv.getNode2().getParent();
            grandParent.removeChild(newConv.getNode2());
            grandParent.addChild(parent);
        }


        if (newConv.getHeight2()>parent.getHeight())
            newConv.setNode2(parent);


        acg.addConversion(newConv);

        logHGF += Math.log(1.0/acg.getConvCount());

        return logHGF;
    }

    public static void main(String[] args) throws Exception {

        //Randomizer.setSeed(1);

        ConversionGraph acg = new ConversionGraph();
        ConstantPopulation popFunc = new ConstantPopulation();

        ClonalFrameConversionSwap operator = new ClonalFrameConversionSwap();
        operator.initByName(
            "weight", 1.0,
            "acg", acg,
            "populationModel", popFunc,
            "delta", new RealParameter("50.0"));
        popFunc.initByName("popSize", new RealParameter("1.0"));

        acg.initByName(
            "sequenceLength", 10000,
//            "fromString", "(0:1.0,1:1.0)2:0.0;");
            "fromString", "[&0,500,0.2,1,800,0.8] (0:1.0,1:1.0)2:0.0;");

        double logHR1, logHR2;
        
        System.out.println(acg.getExtendedNewick(true));
        do {
            logHR1 = operator.createConversion();
        } while (Double.isInfinite(logHR1));

        System.out.println(acg.getExtendedNewick(true));

        do {
            logHR2 = operator.deleteConversion();
        } while (Double.isInfinite(logHR2));

        System.out.println(acg.getExtendedNewick(true));

        System.out.println("logHR1 = " + logHR1);
        System.out.println("logHR2 = " + logHR2);
                
    }
    
}