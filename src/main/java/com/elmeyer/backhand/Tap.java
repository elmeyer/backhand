package com.elmeyer.backhand;

public enum Tap
{
    SINGLE("single"),
    DOUBLE("double"),
    TRIPLE("triple"),
    HELD("held"),

    MAYBE_SINGLE("maybe single"),
    MAYBE_DOUBLE("maybe double"),
    MAYBE_TRIPLE("maybe triple"),
    MAYBE_HELD("maybe held");

    private final String mType;

    Tap(String s) {
        mType = s;
    }
}
