package com.elmeyer.backhand;

import org.opencv.core.Mat;

/**
 * Class implementing the luminance computation over a ROI in an image as a
 * Runnable.
 */
class LumaAnalysisRunnable implements Runnable
{
    private static int mStartRow, mEndRow, mStartCol, mEndCol;
    private Mat mImg;

    public double mLuma = 0;

    /**
     * Constructor.
     * @param startRow Number of the first row to include in the calculation (Range: 0...mImg.rows()-1)
     * @param endRow Number of the first row to exclude from the calculation (Range: 1...mImg.rows())
     * @param startCol Number of the first column to include in the calculation (Range: 0...mImg.cols()-1)
     * @param endCol Number of the first column to exclude from the calculation (Range: 1...mImg.cols())
     * @param img The image to calculate on.
     */
    LumaAnalysisRunnable(int startRow, int endRow, int startCol, int endCol, Mat img)
    {
        if (startRow < endRow && startCol < endCol) {
            this.mStartRow = startRow;
            this.mEndRow = endRow;
            this.mStartCol = startCol;
            this.mEndCol = endCol;
        } else {
            throw new IllegalArgumentException("Invalid range");
        }
        this.mImg = img;
    }

    public void run()
    {
        if (mImg != null) {
            Mat tmpImg = mImg.clone(); // to assure the image doesn't change
            for (int i = mStartRow; i < mEndRow; i++) {
                for (int j = mStartCol; j < mEndCol; j++) {
                    mLuma += tmpImg.get(i, j)[0];
                }
            }

            mLuma = mLuma / (double) ((mEndRow - mStartRow) * (mEndCol - mStartCol));
        }
    }
}
