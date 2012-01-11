package util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Static helper to create collections. 
 * <p>
 * Usage example: 
 * <pre>
 * import static com.joejag.common.collections.Dsl.*;
 *
 * // A list
 * List list = list("abc", "def");
 *	
 * // A set
 * Set set = set("Sleepy", "Sneezy", "Dozy");
 *	
 * // A Map
 * Map map = map(entry("Joe", 28), entry("Gerry", 39));
 * </pre>
 * 
 * 
 * <p>
 * Thanks to http://code.joejag.com/2011/a-dsl-for-collections-in-java/
 * 
 * @author Paolo Di Tommaso
 *
 */
public class Dsl {
    
	public static <T> List<T> list(T... args) {
        return Arrays.asList(args);
    }

    public static <T> Set<T> set(T... args) {
        Set<T> result = new HashSet<T>(args.length);
        result.addAll(Arrays.asList(args));
        return result;
    }

    public static <K, V> Map<K, V> map(Entry<? extends K, ? extends V>... entries) {
        Map<K, V> result = new HashMap<K, V>(entries.length);

        for (Entry<? extends K, ? extends V> entry : entries)
            if (entry.value != null)
                result.put(entry.key, entry.value);

        return result;
    }
    
    public static Map<String,Object> params(String ... pairs) { 
        Map<String, Object> result = new HashMap<String, Object>(pairs.length);

        if( pairs != null ) for( String item : pairs ){ 
        	if( item == null ) continue;
        	
        	String key;
        	String val=null;
        	int p;
        	if( (p=item.indexOf("=")) != -1 ) { 
        		key = item.substring(0,p);
        		val = item.substring(p+1);
        	}
        	else { 
        		key = item;
        	}
        	
        	result.put(key,val);
        }
        
        return result;
    }

    public static <K, V> Map<K, V> treemap(Entry<? extends K, ? extends V>... entries) {
        Map<K, V> result = new TreeMap<K, V>();

        for (Entry<? extends K, ? extends V> entry : entries)
            if (entry.value != null)
                result.put(entry.key, entry.value);

        return result;
    }    
    
    public static <K, V> Entry<K, V> entry(K key, V value) {
        return new Entry<K, V>(key, value);
    }

    public static class Entry<K, V> {
        K key;
        V value;

        public Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }


}