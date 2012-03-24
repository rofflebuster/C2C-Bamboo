package demos;

import c2c.api.Mapper;
import c2c.api.OutputCollector;
import c2c.api.Reducer;

public class WordCount implements Reducer, Mapper {

	@Override
	public void map(String key, String value, OutputCollector collector) {
		String[] words = value.split("\\s+");
		for (String w : words) {
			collector.collect(w, "1");
		}
	}

	@Override
	public void reduce(String key, Iterable<String> values,
			OutputCollector collector) {
		int count = 0;
		for (@SuppressWarnings("unused")
		String s : values) {
			count++;
		}
		collector.collect(key, String.valueOf(count));
	}

}