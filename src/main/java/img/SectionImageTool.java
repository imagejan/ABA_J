package img;

import net.imglib2.type.Type;
import rest.AllenRefVol;
import rest.Atlas;

import mpicbg.spim.data.SpimDataException;

import net.imagej.ops.OpService;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Cursor;
import net.imglib2.algorithm.labeling.AllConnectedComponents;
import net.imglib2.algorithm.labeling.Watershed;
import net.imglib2.algorithm.morphology.Opening;
import net.imglib2.algorithm.morphology.StructuringElements;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.labeling.Labeling;
import net.imglib2.labeling.LabelingType;
import net.imglib2.labeling.NativeImgLabeling;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Function collection for section image operations.
 * TODO: the copy method should not be necessary... but for the NativeType thing, the img.copy() or ops.copy().img() fail between sci-java.pom increments (later than 19.2.0).
 *
 * @author Felix Meyenhofer
 */
@SuppressWarnings("WeakerAccess")
public class SectionImageTool {

    public static <T extends Type<T> & NativeType<T>> Img<T> copy(final Img<T> img) {
        ArrayImgFactory<T> factory = new ArrayImgFactory<>(img.firstElement());

        Img<T> cpy = factory.create(img);

        Cursor<T> targetCursor = cpy.localizingCursor();
        RandomAccess<T> sourceRandomAccess = img.randomAccess();

        while (targetCursor.hasNext()) {
            targetCursor.fwd();
            sourceRandomAccess.setPosition(targetCursor);
            targetCursor.get().set(sourceRandomAccess.get());
        }

        return cpy;
    }

    public static <T extends RealType<T> & NativeType<T>> Img<BitType> createMask(Img<T> rai, OpService ops) {
        long[] dims = new long[rai.numDimensions()];
        rai.dimensions(dims);

        long max = 0;
        for (long dim : dims) {
            if (dim > max) {
                max = dim;
            }
        }

        double sigma = ((double) max) / 100;//TODO: Parameter (be explicit when using this method in a plugin)

        if (sigma < 3) {
            sigma = 3;
        }

        return createMask(rai, sigma, ops);
    }

    public static <T extends RealType<T> & NativeType<T>> Img<BitType> createMask(Img<T> rai, double sigma, OpService ops) {
//        Img<T> fil = rai.copy();
        Img<T> fil = SectionImageTool.copy(rai);
        ops.filter().gauss(fil, sigma);
//        Gauss3.gauss(new double[]{sigma,0}, rai, fil, 1);

        Img<BitType> bw = ops.create().img(fil, new BitType());
        ops.threshold().huang(bw, fil);

        Img<BitType> ope = ops.create().img(bw);
        List<Shape> strel = StructuringElements.diamond(3, 1);
//        ops.morphology().open(ope, bw, strel);
        Opening.open(Views.extendZero(bw), ope, strel, 1);

        Img<BitType> hol = ops.create().img(bw);
        ops.morphology().fillHoles(hol, bw);

        return extractCenterBlob(hol, ops);
    }

    public static <T extends RealType<T>> Img<BitType> extractCenterBlob(RandomAccessibleInterval<BitType> msk, OpService ops) {
        long[] position = new long[msk.numDimensions()];
        long[] upperBounds = new long[msk.numDimensions()];
        msk.max(upperBounds);

        // get the border
        Img<BitType> out = ops.create().img(msk);
        ops.morphology().outline(out, msk, false);

        // invert the outline and kill border pixels
        Cursor<BitType> cursor = out.cursor();
        while (cursor.hasNext()) {
            cursor.localize(position);
            boolean isBorder = false;
            for (int p = 0; p < position.length; p++) {
                if (0 == position[p] || position[p] == upperBounds[p]) {
                    isBorder = true;
                    break;
                }
            }

            if (isBorder) {
                cursor.next().set(true);
            } else {
                cursor.next().not();
            }
        }

        // distance transform
        RandomAccessibleInterval<T> dst = ops.image().distancetransform(out);
        IterableInterval<T> dsti = Views.interval(dst, dst);
//        ImageJFunctions.show(dst, "dist. transform");

        // get the intensity boundaries
        T min = dsti.firstElement().createVariable();
        min.setReal(0.0);
        T absMax = dsti.firstElement().createVariable();
        ops.stats().max(absMax, dsti);

        // Variable to store the maximum within the mask
        T currentMax = dsti.firstElement().createVariable();
        currentMax.set(min);

        // create the seeds for the watershed: the minimum inside the mask and the border another
        // also invert the distance transform
        final NativeImgLabeling<Integer, IntType> sds =
                new NativeImgLabeling<>(new ArrayImgFactory<IntType>().create(msk, new IntType()));

        RandomAccess<LabelingType<Integer>> sdsCursor = sds.randomAccess();
        Cursor<T> dstCursor = dsti.cursor();
        RandomAccess<BitType> mskCursor = msk.randomAccess();

        long[] maxPosision = new long[msk.numDimensions()];
        while (dstCursor.hasNext()) {
            dstCursor.fwd();
            dstCursor.localize(position);
            T dstValue = dstCursor.get();

            mskCursor.setPosition(position);

            // look for the minimum inside the mask
            if (mskCursor.get().get()) {
                if (dstValue.compareTo(currentMax) > 0) {
                    currentMax.set(dstValue);
                    dstCursor.localize(maxPosision);
                }
            }

            // check if it's a border pixel
            boolean isBorder = false;
            for (int p = 0; p < position.length; p++) {
                if (0 == position[p] || position[p] == upperBounds[p]) {
                    isBorder = true;
                    break;
                }
            }

            // create border seeds and invert distance
            if (isBorder) {
                dstValue.set(min);
                sdsCursor.setPosition(position);
                sdsCursor.get().setLabel(2);
            } else {
                dstValue.mul(-1.0);
                dstValue.add(absMax);
            }
        }

        RandomAccess<T> ra = dst.randomAccess();
        ra.setPosition(maxPosision);
        ra.get().set(min);
        sdsCursor.setPosition(maxPosision);
        sdsCursor.get().setLabel(1);

        // watershed
        final NativeImgLabeling<Integer, IntType> blb =
                new NativeImgLabeling<>(new ArrayImgFactory<IntType>().create(msk, new IntType()));
        Watershed<T, IntType> watershed = new Watershed<>();
        watershed.setIntensityImage(dst);
        watershed.setStructuringElement(AllConnectedComponents.getStructuringElement(2));
        watershed.setSeeds((Labeling)sds);
        watershed.setOutputLabeling((Labeling)blb);
        watershed.process();

        // create output mask
        Img<BitType> obj = ops.create().img(msk);

        Cursor<IntType> lblCur = Views.flatIterable(blb.getStorageImg()).cursor();
        Cursor<BitType> bwCur = Views.flatIterable(obj).cursor();
        while (bwCur.hasNext()) {
            bwCur.next().set(lblCur.next().getInteger() == 2);
        }

//        ImageJFunctions.show(msk, "mask");
//        ImageJFunctions.show(out, "outline");
//        ImageJFunctions.show(sds.getStorageImg(), "seeds");
//        ImageJFunctions.show(dst, "modified inv. dist. transform");

        return obj;
    }

    public static int getMaskArea(RandomAccessibleInterval<BitType> msk) {
        Cursor<BitType> cursor = Views.flatIterable(msk).cursor();
        int area = 0;
        while (cursor.hasNext()) {
            if (cursor.next().valueEquals(new BitType(true))) {
                area++;
            }
        }

        return area;
    }

    public static <T extends NativeType<T> & RealType<T>> void maskImage(RandomAccessibleInterval<T> img, RandomAccessibleInterval<BitType> msk) {
        Cursor<T> curImg = Views.flatIterable(img).cursor();
        Cursor<BitType> curMsk = Views.flatIterable(msk).cursor();
        while (curImg.hasNext()) {
            curImg.fwd();
            curMsk.fwd();
            if (!curMsk.get().get()) {
                curImg.get().setZero();
            }
        }
    }

    public static <T extends NativeType<T> & RealType<T>> float normalizedSSD(RandomAccessibleInterval<T> imgA, RandomAccessibleInterval<T> imgB, OpService ops) {
        IterableInterval<T> aIter = Views.iterable(imgA);
        float aMin = ops.stats().min(aIter).getRealFloat();
        float aMax = ops.stats().max(aIter).getRealFloat();
        float aRan = aMax - aMin;

        IterableInterval<T> bIter = Views.iterable(imgB);
        float bMin = ops.stats().min(bIter).getRealFloat();
        float bMax = ops.stats().max(bIter).getRealFloat();
        float bRan = bMax - bMin;

        float ssd = 0;

        Cursor<T> aCur = Views.flatIterable(imgA).cursor();
        Cursor<T> bCur = Views.flatIterable(imgB).cursor();

        while (aCur.hasNext()) {
            T aVal = aCur.next();
            T bVal = bCur.next();

            float a = (aVal.getRealFloat() - aMin) / aRan;
            float b = (bVal.getRealFloat() - bMin) / bRan;

            ssd += Math.pow((a - b), 2);
        }

        return ssd;
    }

    public static <T extends NativeType<T> & RealType<T>> Img<T> double2Whatever(RandomAccessibleInterval<DoubleType> img, Img<T> ori, OpService ops) {
        IterableInterval<DoubleType> imgIter = Views.iterable(img);
        DoubleType miSrc = new DoubleType(ops.stats().min(imgIter).getRealFloat());
        DoubleType maSrc = new DoubleType(ops.stats().max(imgIter).getRealFloat());
        IterableInterval<T> oriIter = Views.iterable(ori);
        DoubleType miTar = new DoubleType(ops.stats().min(oriIter).getRealFloat());
        DoubleType maTar = new DoubleType(ops.stats().max(oriIter).getRealFloat());

        Img con;
        if (ori.firstElement() instanceof UnsignedByteType) {
            con = ops.convert().uint8(ops.image().normalize(imgIter, miSrc, maSrc, miTar, maTar));
        } else if (ori.firstElement() instanceof UnsignedShortType) {
            con = ops.convert().uint16(ops.image().normalize(imgIter, miSrc, maSrc, miTar, maTar));
        } else {
            con = ops.create().img(ops.image().normalize(imgIter, miSrc, maSrc, miTar, maTar));
        }

        return con;
    }

    public static <T extends RealType<T>> double estimateSectionResolution(Img<T> img, Atlas.PlaneOfSection plane, OpService ops) throws TransformerException, IOException, URISyntaxException, SpimDataException {
        double sectionResolution;
        Atlas.VoxelResolution refRes = Atlas.VoxelResolution.FIFTY;
        Img<BitType> refMask = AllenRefVol.getSectionMask(refRes, plane);
        double refArea = ops.stats().sum(refMask).getRealDouble();

        double scale1 = ((double) refMask.dimension(0)) / ((double) img.dimension(0));
        RandomAccessibleInterval<T> secImgSca = Views.subsample(img, Math.round(1 / scale1));

        Img secImgScaImg = ImgView.wrap(secImgSca, new ArrayImgFactory());
        Img<BitType> secMask = SectionImageTool.createMask(secImgScaImg, ops);

        double secArea = ops.stats().sum(secMask).getRealDouble();
        double scale2 = refArea / secArea;
        sectionResolution = refRes.getValue() * scale1 * scale2;

        return sectionResolution;
    }
}
