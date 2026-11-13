package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 终端字符宽度计算：CJK 宽字符、组合符零宽、控制字符按不可见处理。 */
public class WcWidthTest {

    @Test
    public void asciiCharactersHaveWidthOne() {
        assertEquals(1, WcWidth.width('a'));
        assertEquals(1, WcWidth.width('0'));
        assertEquals(1, WcWidth.width(' '));
    }

    @Test
    public void cjkAndFullWidthCharactersHaveWidthTwo() {
        assertEquals(2, WcWidth.width('中'));
        assertEquals(2, WcWidth.width('日'));
        assertEquals(2, WcWidth.width('한'));
        assertEquals(2, WcWidth.width('，'));
    }

    @Test
    public void combiningMarksHaveWidthZero() {
        // U+0301 COMBINING ACUTE ACCENT
        assertEquals(0, WcWidth.width(0x0301));
    }

    @Test
    public void controlCharactersAreInvisible() {
        assertEquals(0, WcWidth.width(0x00));
        assertEquals(0, WcWidth.width(0x07));
        assertEquals(0, WcWidth.width(0x1B));
    }
}
