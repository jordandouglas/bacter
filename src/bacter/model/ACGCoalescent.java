/*
 * Copyright (C) 2013 Tim Vaughan <tgvaughan@gmail.com>
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
package bacter.model;

import bacter.CFEventList;
import bacter.Conversion;
import bacter.ConversionGraph;
import bacter.model.ACGDistribution;
import beast.core.Description;
import beast.core.Input;
import beast.core.State;
import beast.core.parameter.RealParameter;
import beast.evolution.alignment.Alignment;
import beast.evolution.tree.coalescent.PopulationFunction;
import beast.math.GammaFunction;
import feast.input.In;
import java.util.List;
import java.util.Random;

/**
 * @author Tim Vaughan <tgvaughan@gmail.com>
 */
@Description("Appoximation to the coalescent with gene conversion.")
public class ACGCoalescent extends ACGDistribution {
    
    public Input<PopulationFunction> popFuncInput = new In<PopulationFunction>(
            "populationModel", "Population model.").setRequired();
    
    public Input<RealParameter> rhoInput = new In<RealParameter>("rho",
            "Recombination rate parameter.").setRequired();
    
    public Input<RealParameter> deltaInput = new In<RealParameter>("delta",
            "Tract length parameter.").setRequired();

    public Input<Integer> lowerCCBoundInput = new In<Integer>("lowerConvCountBound",
            "Lower bound on conversion count.").setDefault(0);

    public Input<Integer> upperCCBoundInput = new In<Integer>("upperConvCountBound",
            "Upper bound on conversion count.").setDefault(Integer.MAX_VALUE);

    PopulationFunction popFunc;
    int sequenceLength;
    
    public ACGCoalescent() { }
    
    @Override
    public void initAndValidate() throws Exception {
        super.initAndValidate();
        
        popFunc = popFuncInput.get();
    }
    
    @Override
    public double calculateACGLogP() {

        // Check whether conversion count exceeds bounds.
        if (acg.getTotalConvCount()<lowerCCBoundInput.get()
                || acg.getTotalConvCount()>upperCCBoundInput.get())
            return Double.NEGATIVE_INFINITY;

        logP = calculateClonalFrameLogP();
        
        // Probability of conversion count:
        if (rhoInput.get().getValue()>0.0) {
            double poissonMean = rhoInput.get().getValue()
                    *acg.getClonalFrameLength()
                    *(acg.getTotalSequenceLength()
                    +acg.getAlignments().size()*deltaInput.get().getValue());
            logP += -poissonMean + acg.getTotalConvCount()*Math.log(poissonMean);
            //      - GammaFunction.lnGamma(acg.getConvCount()+1);
        } else {
            if (acg.getTotalConvCount()>0)
                logP = Double.NEGATIVE_INFINITY;
        }
        

        for (Alignment alignment : acg.getAlignments())
            for (Conversion conv : acg.getConversions(alignment))
                logP += calculateConversionLogP(conv);
        
        // This N! takes into account the permutation invariance of
        // the individual conversions, and cancels with the N! in the
        // denominator of the Poissonian above.
        // logP += GammaFunction.lnGamma(acg.getConvCount() + 1);
        
        
        return logP;        
    }

    /**
     * Compute probability of clonal frame under coalescent.
     * 
     * @return log(P)
     */
    public double calculateClonalFrameLogP() {
        
        List<CFEventList.Event> events = acg.getCFEvents();
        
        double thisLogP = 0.0;
        
        for (int i=0; i<events.size()-1; i++) {
            double timeA = events.get(i).getHeight();
            double timeB = events.get(i+1).getHeight();

            double intervalArea = popFunc.getIntegral(timeA, timeB);
            int k = events.get(i).getLineageCount();
            thisLogP += -0.5*k*(k-1)*intervalArea;
            
            if (events.get(i+1).getType()==CFEventList.EventType.COALESCENCE)
                thisLogP += Math.log(1.0/popFunc.getPopSize(timeB));
        }
        
        return thisLogP;
    }
    
    /**
     * Compute probability of recombinant edges under conditional coalescent.
     * @param conv
     * @return log(P)
     */
    public double calculateConversionLogP(Conversion conv) {

        double thisLogP = 0.0;
        
        List<CFEventList.Event> events = acg.getCFEvents();
        
        // Probability density of location of recombinant edge start
        thisLogP += Math.log(1.0/acg.getClonalFrameLength());

        // Identify interval containing the start of the recombinant edge
        int startIdx = 0;
        while (events.get(startIdx+1).getHeight() < conv.getHeight1())
            startIdx += 1;
        
        for (int i=startIdx; i<events.size() && events.get(i).getHeight()<conv.getHeight2(); i++) {
            
            double timeA = Math.max(events.get(i).getHeight(), conv.getHeight1());
            
            double timeB;
            if (i<events.size()-1)
                timeB = Math.min(conv.getHeight2(), events.get(i+1).getHeight());
            else
                timeB = conv.getHeight2();
            
            double intervalArea = popFunc.getIntegral(timeA, timeB);
            thisLogP += -events.get(i).getLineageCount()*intervalArea;
        }
        
        // Probability of single coalescence event
        thisLogP += Math.log(1.0/popFunc.getPopSize(conv.getHeight2()));

        // Probability of start site:
        if (conv.getStartSite()==0)
            thisLogP += Math.log((deltaInput.get().getValue() + 1)
                /(acg.getAlignments().size()*deltaInput.get().getValue()
                    + acg.getTotalSequenceLength()));
        else
            thisLogP += Math.log(
                    1.0/(acg.getAlignments().size()*deltaInput.get().getValue()
                            + acg.getTotalSequenceLength()));

        // Probability of end site:
        double probEnd = Math.pow(1.0-1.0/deltaInput.get().getValue(),
            conv.getEndSite() - conv.getStartSite())
            / deltaInput.get().getValue();
        
        // Include probability of going past the end:
        if (conv.getEndSite() == acg.getSequenceLength(conv.getAlignment())-1)
            probEnd += Math.pow(1.0-1.0/deltaInput.get().getValue(),
                    acg.getSequenceLength(conv.getAlignment())-conv.getStartSite());

        thisLogP += Math.log(probEnd);
        
        return thisLogP;
    }

    @Override
    protected boolean requiresRecalculation() {
        return true;
    }

    @Override
    public List<String> getArguments() {
        return null; // Doesn't seem to be used...
    }

    @Override
    public List<String> getConditions() {
        return null; // Doesn't seem to be used...
    }

    @Override
    public void sample(State state, Random random) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}