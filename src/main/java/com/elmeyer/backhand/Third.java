package com.elmeyer.backhand;

enum Third {
    TOP(0), CENTER_HORIZ(1), BOTTOM(2), LEFT(3), CENTER_VERT(4), RIGHT(5);

    public final int which;

    Third(int which)
    {
        this.which = which;
    }
}
