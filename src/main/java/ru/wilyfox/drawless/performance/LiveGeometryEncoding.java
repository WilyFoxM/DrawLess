package ru.wilyfox.drawless.performance;

/** Indexed vertex pulling: the index selects both a shared local vertex and its live instance. */
public final class LiveGeometryEncoding {
    public static final int VERTEX_BITS=20;
    public static final int MAX_VERTICES=1<<VERTEX_BITS;
    public static final int MAX_INSTANCES=1<<11;
    public static final int VERTEX_BYTES=32;
    public static final int INSTANCE_BYTES=128;
    private LiveGeometryEncoding(){}
    public static int index(int vertex,int instance){
        if(vertex<0||vertex>=MAX_VERTICES||instance<0||instance>=MAX_INSTANCES)
            throw new IllegalArgumentException("Live geometry index exceeds its packed range");
        return instance<<VERTEX_BITS|vertex;
    }
    public static int vertex(int index){return index&(MAX_VERTICES-1);}
    public static int instance(int index){return index>>>VERTEX_BITS;}
}
