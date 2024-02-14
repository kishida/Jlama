package com.github.tjake.jlama.model;

import com.github.tjake.jlama.math.ActivationFunction;
import com.github.tjake.jlama.math.VectorMath;
import com.github.tjake.jlama.model.functions.FeedForward;
import com.github.tjake.jlama.tensor.AbstractTensor;
import com.github.tjake.jlama.tensor.operations.TensorOperationsProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A Mixed of Experts block. See https://huggingface.co/blog/moe for more details
 */
public class MOEBlock implements FeedForward {

    private final AbstractModel model;
    private final AbstractTensor moeGateWeight;
    private final int numberOfExperts;
    private final int numberOfExpertsPerToken;
    private final AbstractTensor fullyConnectedWeights[];
    private final AbstractTensor projectionWeights[];
    private final AbstractTensor upProjectionWeights[];
    private final float[] expertResults;
    private final int[] selectedExperts;
    private final ActivationFunction.Type activationFunction;

    private final AbstractTensor[] batchResults;
    private final AbstractTensor[] batchWeights;


    public MOEBlock(AbstractModel model, int numberOfExperts, int numberOfExpertsPerToken, ActivationFunction.Type activationFunction, AbstractTensor moeGateWeight, AbstractTensor[] fullyConnectedWeights, AbstractTensor[] projectionWeights, AbstractTensor[] upProjectionWeights) {
        this.model = model;
        this.numberOfExperts = numberOfExperts;
        this.numberOfExpertsPerToken = numberOfExpertsPerToken;
        this.moeGateWeight = moeGateWeight;
        this.activationFunction = activationFunction;
        this.fullyConnectedWeights = fullyConnectedWeights;
        this.projectionWeights = projectionWeights;
        this.upProjectionWeights = upProjectionWeights;
        this.expertResults = new float[numberOfExperts];
        this.selectedExperts = new int[numberOfExpertsPerToken];
        this.batchResults = new AbstractTensor[2];
        this.batchWeights = new AbstractTensor[2];
    }

    @Override
    public AbstractTensor forward(AbstractTensor lnemb, Optional<Consumer<List<AbstractTensor>>> tensorReducer) {

        int hiddenLength = model.c.hiddenLength;
        AbstractTensor result = model.makeTensor(model.c.embeddingLength);

        try (AbstractTensor buf = model.makeTensor(hiddenLength); AbstractTensor buf2 = model.makeTensor(hiddenLength); AbstractTensor moeResult = model.makeTensor(model.c.embeddingLength)) {

            // Apply each experts gate to the input
            VectorMath.pfor(0, numberOfExperts, i -> {
                expertResults[i] = TensorOperationsProvider.get().dotProduct(lnemb, moeGateWeight.slice(true, i), model.c.embeddingSegmentLength());
            });

            // Pick the top experts for this token
            VectorMath.softMax(expertResults);
            topk(expertResults);

            // Apply the selected experts to the input
            for (int i = 0; i < numberOfExpertsPerToken; i++) {
                batchWeights[0] = fullyConnectedWeights[selectedExperts[i]];
                batchWeights[1] = upProjectionWeights[selectedExperts[i]];
                AbstractTensor projectionWeight = projectionWeights[selectedExperts[i]];
                batchResults[0] = buf;
                batchResults[1] = buf2;

                VectorMath.pchunk(0, hiddenLength, (chunkStart, chunkSize) -> {
                    TensorOperationsProvider.get().dotProductBatchChunk(batchResults, lnemb, batchWeights, model.c.embeddingSegmentStart(), model.c.embeddingSegmentLength(), chunkStart, chunkSize);
                });

                tensorReducer.ifPresent(func -> {
                    List<AbstractTensor> ts = new ArrayList<>(2);
                    ts.add(buf);
                    ts.add(buf2);
                    func.accept(ts);
                });

                VectorMath.pfor(0, hiddenLength, iv -> {
                    float w1 = buf.get(iv);
                    float w1a = ActivationFunction.eval(activationFunction, w1);
                    buf.set(w1a, iv);
                });

                TensorOperationsProvider.get().maccumulate(buf, buf2, 0, hiddenLength);

                //matmul the projection and sum into result
                VectorMath.pchunk(model.c.embeddingSegmentStart(), model.c.embeddingSegmentLength(), (chunkStart, chunkSize) -> {
                    TensorOperationsProvider.get().dotProductChunk(moeResult, buf, projectionWeight, 0, hiddenLength, chunkStart, chunkSize);
                });

                if (i == 0) {
                    result.copyFrom(moeResult, model.c.embeddingSegmentStart(), model.c.embeddingSegmentStart(), model.c.embeddingSegmentLength());
                } else {
                    TensorOperationsProvider.get().accumulate(result, moeResult, model.c.embeddingSegmentStart(), model.c.embeddingSegmentLength());
                }
            }

            return result;
        }
    }

    private int[] topk(float[] probs) {
        for (int i = 0; i < numberOfExpertsPerToken; i++) {
            selectedExperts[i] = i;
        }
        for (int i = numberOfExpertsPerToken; i < probs.length; i++) {
            int min = 0;
            for (int j = 1; j < numberOfExpertsPerToken; j++) {
                if (probs[selectedExperts[j]] < probs[selectedExperts[min]]) {
                    min = j;
                }
            }
            if (probs[i] > probs[selectedExperts[min]]) {
                selectedExperts[min] = i;
            }
        }
        return selectedExperts;
    }
}
