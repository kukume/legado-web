package android.util;

import java.util.TreeMap;

/**
 * Android SparseArray 的最小兼容实现（移植的 MOBI 解析库用到）：int 键到对象的映射，键有序
 */
public class SparseArray<E> {

    private final TreeMap<Integer, E> map = new TreeMap<>();

    public SparseArray() {
    }

    public SparseArray(int initialCapacity) {
    }

    /** 没有这个键时返回 null（与 Android 相同） */
    public E get(int key) {
        return map.get(key);
    }

    public E get(int key, E valueIfKeyNotFound) {
        E v = map.get(key);
        return v != null ? v : valueIfKeyNotFound;
    }

    public void put(int key, E value) {
        map.put(key, value);
    }

    /** Kotlin 的 sparseArray[key] = value */
    public void set(int key, E value) {
        map.put(key, value);
    }

    public void remove(int key) {
        map.remove(key);
    }

    public void delete(int key) {
        map.remove(key);
    }

    public int size() {
        return map.size();
    }

    public boolean contains(int key) {
        return map.containsKey(key);
    }

    public int keyAt(int index) {
        int i = 0;
        for (Integer k : map.keySet()) {
            if (i++ == index) return k;
        }
        throw new ArrayIndexOutOfBoundsException(index);
    }

    public E valueAt(int index) {
        return map.get(keyAt(index));
    }

    public int indexOfKey(int key) {
        int i = 0;
        for (Integer k : map.keySet()) {
            if (k == key) return i;
            i++;
        }
        return -1;
    }

    public void clear() {
        map.clear();
    }
}
