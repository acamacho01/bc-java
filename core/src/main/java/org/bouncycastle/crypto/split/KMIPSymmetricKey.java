package org.bouncycastle.crypto.split;

public class KMIPSymmetricKey
{
    private KMIPKeyBlock keyBlock; // The KeyBlock that holds the actual key

    public KMIPSymmetricKey(KMIPKeyBlock keyBlock)
    {
        this.keyBlock = keyBlock;
    }

    public KMIPKeyBlock getKeyBlock()
    {
        return keyBlock;
    }

    public void setKeyBlock(KMIPKeyBlock keyBlock)
    {
        this.keyBlock = keyBlock;
    }
}
