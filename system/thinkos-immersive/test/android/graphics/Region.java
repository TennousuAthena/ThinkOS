package android.graphics;

/** Only rectangle containment is needed by the framework-IME boundary test. */
public final class Region {
    private final int left,top,right,bottom;
    public Region(int left,int top,int right,int bottom) { this.left=left;this.top=top;this.right=right;this.bottom=bottom; }
    public boolean contains(int x,int y) { return x>=left && x<right && y>=top && y<bottom; }
}
