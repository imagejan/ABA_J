import img.SectionImageOutline;
import img.SectionImageTool;

import net.imagej.ImgPlus;
import net.imagej.ops.OpService;
import net.imglib2.*;
import net.imglib2.algorithm.morphology.Dilation;
import net.imglib2.algorithm.morphology.StructuringElements;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import java.util.List;

/**
 * @author Felix Meyenhofer
 */
@Plugin(type = Command.class, menuPath = "Plugins > Allen Brain Atlas > 1. Pre-Processing > Orient + Auto-Crop Section")
public class SectionImagePreprocessing<T extends RealType<T> & NativeType<T>> implements Command {

    @Parameter
    private OpService opService;

    @Parameter
    private UIService uiService;

    @Parameter
    private StatusService statusService;


    @Parameter(label = "Input section")
    private ImgPlus<T> inputSection;

    @Parameter(label = "Crop margin")
    private long margin = 50;

    @Parameter(label = "Mask section image")
    private boolean maskSection = true;

    @Parameter(label = "Show image mask")
    private boolean showMask = false;

    @Parameter(type = ItemIO.OUTPUT)
    private ImgPlus outputSection;


    @Override
    public void run() {
        outputSection = cropAndRotate(inputSection, margin, maskSection, showMask, opService, uiService, statusService);
    }

    static <T extends RealType<T> & NativeType<T>> ImgPlus<T> cropAndRotate(ImgPlus<T> img, long margin, boolean masking, boolean show,
                                     OpService ops, UIService uis, StatusService status) {
        status.showStatus(0, 100, "process input");

        RandomAccessibleInterval<DoubleType> imgSrc = ops.convert().float64(img);

        double factor = 500.0 / (double) img.dimension(0);  //TODO: figure out an acceptable precision given an image size and determine the scaling accordingly
        double[] fwdScale = new double[img.numDimensions()];
        double[] invScale = new double[img.numDimensions()];
        for (int d = 0; d < img.numDimensions(); d++) {
            fwdScale[d] = factor;
            invScale[d] = 1 / factor;
        }

        status.showStatus(5, 100, "process input");
        RandomAccessibleInterval<DoubleType> sca = ops.transform().scaleView(imgSrc, fwdScale, new NLinearInterpolatorFactory<>());

        status.showStatus(10, 100,"create section mask");
        Img<BitType> msk = SectionImageTool.createMask(ImgView.wrap(sca, new ArrayImgFactory<>()), ops);

        if (masking) {
            status.showStatus(20, 100, "masking section image");
            RandomAccessibleInterval<BitType> mskSca = ops.transform().scaleView(msk, invScale, new NearestNeighborInterpolatorFactory<>());
//            ImageJFunctions.show(mskSca);
            Img<BitType> mskScaDil = ops.create().img(mskSca);
            List<Shape> strel = StructuringElements.diamond(2, 2);
            Dilation.dilate(mskSca, mskScaDil, strel, 1);
            SectionImageTool.maskImage(imgSrc, mskScaDil);
        }

        if (show) {
            uis.show(msk);
        }

        status.showStatus(30, 100,"create mask outline");
        RandomAccessibleInterval<BitType> out = ops.morphology().outline(msk, false);

        status.showStatus(40, 100, "contour analysis");
        SectionImageOutline sampler = new SectionImageOutline(out, 4);
        sampler.doPca();
        double theta = sampler.getRotation();
        double[] bb = sampler.getRotatedBoundingBox();

        // scale up
        long[] bbt = new long[4];
        for (int i = 0; i < 4; i++) {
            bbt[i] = (long) (bb[i] / factor);
        }

        long[] ulct = new long[]{bbt[0] - margin, bbt[1] - margin};
        long[] lrct = new long[]{bbt[2] + margin, bbt[3] + margin};

//        System.out.println("ulc and lrc transformed");
//        System.out.println(ArrayUtils.toString(ulct));
//        System.out.println(ArrayUtils.toString(lrct));
        Interval boundingBox = new FinalInterval(ulct, lrct);

        status.showStatus(50,100, "rotate");
        AffineTransform2D t = new AffineTransform2D();
        t.rotate(theta);

        // Determine the new bounds
        double[][] corners = new double[][]{
                {imgSrc.min(0), imgSrc.min(1)},
                {imgSrc.min(0), imgSrc.max(1)},
                {imgSrc.max(0), imgSrc.min(1)},
                {imgSrc.max(0), imgSrc.max(1)}
        };
        double xmint = Double.MAX_VALUE;
        double xmaxt = 0;
        double ymint = Double.MAX_VALUE;
        double ymaxt = 0;
        for (double[] corner : corners) {
            double[] cornert = new double[2];
            t.apply(corner, cornert);
            xmint = Math.min(xmint, cornert[0]);
            xmaxt = Math.max(xmaxt, cornert[0]);
            ymint = Math.min(ymint, cornert[1]);
            ymaxt = Math.max(ymaxt, cornert[1]);
        }
        Interval interval = new FinalInterval(new long[]{(long) xmint, (long) ymint},
                                              new long[]{(long) xmaxt, (long) ymaxt});

        // Rotate the section image
        RealRandomAccessible<DoubleType> interp = Views.interpolate(Views.extendZero(imgSrc), new NLinearInterpolatorFactory());
        RealRandomAccessible<DoubleType> transf = RealViews.transform(interp, t);
        RandomAccessibleInterval<DoubleType> raster = Views.interval(Views.raster(transf), interval);
//        ImageJFunctions.show(raster);
        status.showStatus(60,100, "crop");
        RandomAccessibleInterval<DoubleType> crop = ops.transform().crop(raster, boundingBox);
//        RandomAccessibleInterval crop = Views.interval(Views.extendZero(raster), boundingBox); // This does not work for some reason

        status.showStatus(80, 100, "intensity scaling");
        Img<T> imgTar = SectionImageTool.double2Whatever(crop, img, ops);

        ImgPlus<T> output = new ImgPlus(imgTar, img);

        status.showStatus(100,100, "done");

        return output;
    }
}

