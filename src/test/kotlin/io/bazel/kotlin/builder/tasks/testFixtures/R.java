package com.uber;

/** Resource-shaped fields covering mutable IDs and inlined constants in the same class. */
public final class R {
  public static final class styleable {
    public static final int[] Widget = new int[] {0x7f010001};
    public static final int Widget_title = 0;
  }

  public static final class string {
    public static int mutable = 0x7f010001;
    public static final int constant = 0x7f010002;
    public static int qualified = 0x7f010003;
    public static int unused = 0x7f010004;
  }
}
