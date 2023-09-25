package com.github.tjake.jlama.microbench;

import com.github.tjake.jlama.tensor.BFloat16BufferTensor;
import com.github.tjake.jlama.tensor.FloatBufferTensor;
import com.github.tjake.jlama.tensor.Q4ByteBufferTensor;
import com.github.tjake.jlama.tensor.Q8ByteBufferTensor;
import com.github.tjake.jlama.tensor.TensorCache;
import com.github.tjake.jlama.tensor.operations.PanamaTensorOperations;
import com.github.tjake.jlama.tensor.operations.TensorOperations;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


@Warmup(iterations = 1, time = 5)
@Measurement(iterations = 3, time = 5)
@Fork(warmups = 1, value = 1, jvmArgsPrepend = {
        "--add-modules=jdk.incubator.vector",
        "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--enable-preview", "-XX:+PreserveFramePointer", "-XX:+UnlockDiagnosticVMOptions", "-XX:CompilerDirectivesFile=inlinerules.json",
        "--enable-native-access=ALL-UNNAMED"})
public class TensorBench {
    private static final TensorOperations ops = new PanamaTensorOperations();
    private static final int SIZE = 8192;
    @State(Scope.Benchmark)
    public static class Parameters {
        final TensorCache cache = new TensorCache(4096 * 4096);
        final FloatBufferTensor f = new FloatBufferTensor(SIZE);
        final FloatBufferTensor f2 = new FloatBufferTensor(SIZE);
        final BFloat16BufferTensor bf;
        final Q8ByteBufferTensor q8;

        final Q4ByteBufferTensor q4;
        public Parameters() {

            for (int i = 0; i < SIZE; i++)
            {
                f.set(ThreadLocalRandom.current().nextFloat(), i);
                f2.set(ThreadLocalRandom.current().nextFloat(), i);
            }
            this.bf = new BFloat16BufferTensor(f);
            this.q8 = new Q8ByteBufferTensor(f2);
            this.q4 = new Q4ByteBufferTensor(f2);

        }
    }

    /*@Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode(Mode.Throughput)
    public void allocateTensorDirectly(Parameters params, Blackhole bh) {
        try(FloatBufferTensor b = new FloatBufferTensor(1024, 1024)) {
            bh.consume(b);
        }
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode(Mode.Throughput)
    public void allocateTensorCached(Parameters params, Blackhole bh) {
        try(AbstractTensor b = params.cache.get(DType.F32, 1024, 1024)) {
            bh.consume(b);
        }
    }*/


    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode(Mode.Throughput)
    public void b16Tof32B(Parameters p, Blackhole bh) {
        bh.consume(ops.dotProduct(p.f, p.f2, 0, 0, SIZE));
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[]{"-prof", "gc", "TensorBench"});
    }
}
