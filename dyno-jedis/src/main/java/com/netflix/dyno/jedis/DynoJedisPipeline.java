package com.netflix.dyno.jedis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.BinaryClient.LIST_POSITION;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.RedisPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.SortingParams;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;

import com.netflix.dyno.connectionpool.BaseOperation;
import com.netflix.dyno.connectionpool.Connection;
import com.netflix.dyno.connectionpool.exception.DynoException;
import com.netflix.dyno.connectionpool.exception.FatalConnectionException;
import com.netflix.dyno.connectionpool.impl.ConnectionPoolImpl;
import com.netflix.dyno.jedis.JedisConnectionFactory.JedisConnection;

@NotThreadSafe
public class DynoJedisPipeline implements RedisPipeline, AutoCloseable {

	private static final Logger Logger = LoggerFactory.getLogger(DynoJedisPipeline.class);

	// ConnPool and connection to exec the pipeline
	private final ConnectionPoolImpl<Jedis> connPool;
	private volatile Connection<Jedis> connection;

	// the cached pipeline
	private volatile Pipeline jedisPipeline = null;
	// the cached row key for the pipeline. all subsequent requests to pipeline must be the same. this is used to check that.
	private final AtomicReference<String> theKey = new AtomicReference<String>(null); 
	// used for tracking errors
	private final AtomicReference<DynoException> pipelineEx = new AtomicReference<DynoException>(null);

	private static final String DynoPipeline = "DynoPipeline";

	DynoJedisPipeline(ConnectionPoolImpl<Jedis> cPool) {
		this.connPool = cPool;
	}

	private void checkKey(final String key) {

		if (theKey.get() != null) {
			verifyKey(key);

		} else {

			boolean success = theKey.compareAndSet(null, key);
			if (!success) {
				// someone already beat us to it. that's fine, just verify that the key is the same
				verifyKey(key);
			} else {

				connection = connPool.getConnectionForOperation(new BaseOperation<Jedis, String>() {

					@Override
					public String getName() {
						return DynoPipeline;
					}

					@Override
					public String getKey() {
						return key;
					}
				});
			}

			Jedis jedis = ((JedisConnection)connection).getClient();
			jedisPipeline = jedis.pipelined();
		}
	}

	private void verifyKey(final String key) {

		if (!theKey.get().equals(key)) {
			try { 
				throw new RuntimeException("Must have same key for Redis Pipeline in Dynomite");
			} finally {
				discardPipelineAndReleaseConnection();
			}
		}
	}

	private abstract class PipelineOperation<R> {

		abstract R execute(Pipeline jedisPipeline) throws DynoException;

		R execute(final String key) {
			checkKey(key);
			try {

				return execute(jedisPipeline);

			} catch (JedisConnectionException ex) {
				pipelineEx.set(new FatalConnectionException(ex).setAttempt(1));
				throw ex;
			}
		}
	}

	@Override
	public Response<Long> append(final String key, final String value) {

		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.append(key, value);
			}

		}.execute(key);
	}

	@Override
	public Response<List<String>> blpop(final String arg) {

		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.blpop(arg);
			}
		}.execute(arg);

	}

	@Override
	public Response<List<String>> brpop(final String arg) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.brpop(arg);
			}
		}.execute(arg);

	}

	@Override
	public Response<Long> decr(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.decr(key);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> decrBy(final String key, final long integer) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.decrBy(key, integer);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> del(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.del(key);
			}
		}.execute(key);

	}

	@Override
	public Response<String> echo(final String string) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.echo(string);
			}
		}.execute(string);

	}

	@Override
	public Response<Boolean> exists(final String key) {
		return new PipelineOperation<Response<Boolean>>() {

			@Override
			Response<Boolean> execute(final Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.exists(key);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> expire(final String key, final int seconds) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(final Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.expire(key, seconds);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> expireAt(final String key, final long unixTime) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(final Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.expireAt(key, unixTime);
			}
		}.execute(key);

	}

	@Override
	public Response<String> get(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.get(key);
			}
		}.execute(key);

	}

	@Override
	public Response<Boolean> getbit(final String key, final long offset) {
		return new PipelineOperation<Response<Boolean>>() {

			@Override
			Response<Boolean> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.getbit(key, offset);
			}
		}.execute(key);

	}

	@Override
	public Response<String> getrange(final String key, final long startOffset, final long endOffset) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.getrange(key, startOffset, endOffset);
			}
		}.execute(key);

	}

	@Override
	public Response<String> getSet(final String key, final String value) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.getSet(key, value);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> hdel(final String key, final String... field) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hdel(key, field);
			}
		}.execute(key);

	}

	@Override
	public Response<Boolean> hexists(final String key, final String field) {
		return new PipelineOperation<Response<Boolean>>() {

			@Override
			Response<Boolean> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hexists(key, field);
			}
		}.execute(key);

	}

	@Override
	public Response<String> hget(final String key, final String field) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hget(key, field);
			}
		}.execute(key);

	}

	@Override
	public Response<Map<String, String>> hgetAll(final String key) {
		return new PipelineOperation<Response<Map<String, String>>>() {

			@Override
			Response<Map<String, String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hgetAll(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> hincrBy(final String key, final String field, final long value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hincrBy(key, field, value);
			}
		}.execute(key);
	}

	@Override
	public Response<Set<String>> hkeys(final String key) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hkeys(key);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> hlen(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hlen(key);
			}
		}.execute(key);

	}

	@Override
	public Response<List<String>> hmget(final String key, final String... fields) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hmget(key, fields);
			}
		}.execute(key);

	}

	@Override
	public Response<String> hmset(final String key, final Map<String, String> hash) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hmset(key, hash);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> hset(final String key, final String field, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hset(key, field, value);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> hsetnx(final String key, final String field, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hsetnx(key, field, value);
			}
		}.execute(key);

	}

	@Override
	public Response<List<String>> hvals(final String key) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.hvals(key);
			}
		}.execute(key);

	}

	@Override
	public Response<Long> incr(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.incr(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> incrBy(final String key, final long integer) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.incrBy(key, integer);
			}

		}.execute(key);

	}

	@Override
	public Response<String> lindex(final String key, final long index) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lindex(key, index);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> linsert(final String key, final LIST_POSITION where, final String pivot, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return linsert(key, where, pivot, value);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> llen(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.llen(key);
			}

		}.execute(key);

	}

	@Override
	public Response<String> lpop(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lpop(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> lpush(final String key, final String... string) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lpush(key, string);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> lpushx(final String key, final String... string) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lpushx(key, string);
			}

		}.execute(key);

	}

	@Override
	public Response<List<String>> lrange(final String key, final long start, final long end) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lrange(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> lrem(final String key, final long count, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lrem(key, count, value);
			}

		}.execute(key);

	}

	@Override
	public Response<String> lset(final String key, final long index, final String value) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.lset(key, index, value);
			}

		}.execute(key);

	}

	@Override
	public Response<String> ltrim(final String key, final long start, final long end) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.ltrim(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> move(final String key, final int dbIndex) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.move(key, dbIndex);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> persist(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.persist(key);
			}

		}.execute(key);

	}

	@Override
	public Response<String> rpop(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.rpop(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> rpush(final String key, final String... string) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.rpush(key, string);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> rpushx(final String key, final String... string) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.rpushx(key, string);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> sadd(final String key, final String... member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.sadd(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> scard(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.scard(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Boolean> sismember(final String key, final String member) {
		return new PipelineOperation<Response<Boolean>>() {

			@Override
			Response<Boolean> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.sismember(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<String> set(final String key, final String value) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.set(key, value);
			}

		}.execute(key);

	}

	@Override
	public Response<Boolean> setbit(final String key, final long offset, final boolean value) {
		return new PipelineOperation<Response<Boolean>>() {

			@Override
			Response<Boolean> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.setbit(key, offset, value);
			}

		}.execute(key);

	}

	@Override
	public Response<String> setex(final String key, final int seconds, final String value) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.setex(key, seconds, value);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> setnx(final String key, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.setnx(key, value);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> setrange(final String key, final long offset, final String value) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.setrange(key, offset, value);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> smembers(final String key) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.smembers(key);
			}

		}.execute(key);

	}

	@Override
	public Response<List<String>> sort(final String key) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.sort(key);
			}

		}.execute(key);

	}

	@Override
	public Response<List<String>> sort(final String key, final SortingParams sortingParameters) {
		return new PipelineOperation<Response<List<String>>>() {

			@Override
			Response<List<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.sort(key, sortingParameters);
			}

		}.execute(key);

	}

	@Override
	public Response<String> spop(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.spop(key);
			}

		}.execute(key);

	}

	@Override
	public Response<String> srandmember(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.srandmember(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> srem(final String key, final String... member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.srem(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> strlen(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.strlen(key);
			}

		}.execute(key);

	}

	@Override
	public Response<String> substr(final String key, final int start, final int end) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.substr(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> ttl(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.ttl(key);
			}

		}.execute(key);

	}

	@Override
	public Response<String> type(final String key) {
		return new PipelineOperation<Response<String>>() {

			@Override
			Response<String> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.type(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zadd(final String key, final double score, final String member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zadd(key, score, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zcard(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zcard(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zcount(final String key, final double min, final double max) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zcount(key, min, max);
			}

		}.execute(key);

	}

	@Override
	public Response<Double> zincrby(final String key, final double score, final String member) {
		return new PipelineOperation<Response<Double>>() {

			@Override
			Response<Double> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zincrby(key, score, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrange(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrange(key, start, end);
			}

		}.execute(key);

	}


	@Override
	public Response<Set<String>> zrangeByScore(final String key, final double min, final double max) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeByScore(key, min, max);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrangeByScore(final String key, final String min, final String max) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeByScore(key, min, max);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrangeByScore(final String key, final double min, final double max, final int offset, final int count) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeByScore(key, min, max, offset, count);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrangeByScoreWithScores(final String key, final double min, final double max) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeByScoreWithScores(key, min, max);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrangeByScoreWithScores(final String key, final double min, final double max, final int offset, final int count) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeByScoreWithScores(key, min, max, offset, count);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrevrangeByScore(final String key, final double max, final double min) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return zrevrangeByScore(key, max, min);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrevrangeByScore(final String key, final String max, final String min) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrevrangeByScore(key, max, min);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrevrangeByScore(final String key, final double max, final double min, final int offset, final int count) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return zrevrangeByScore(key, max, min, offset, count);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrevrangeByScoreWithScores(final String key, final double max, final double min) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrevrangeByScoreWithScores(key, max, min);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrevrangeByScoreWithScores(final String key, final double max, final double min, final int offset, final int count) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return zrevrangeByScoreWithScores(key, max, min, offset, count);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrangeWithScores(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrangeWithScores(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zrank(final String key, final String member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrank(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zrem(final String key, final String... member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrem(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zremrangeByRank(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zremrangeByRank(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zremrangeByScore(final String key, final double start, final double end) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zremrangeByScore(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<String>> zrevrange(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Set<String>>>() {

			@Override
			Response<Set<String>> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrevrange(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Set<Tuple>> zrevrangeWithScores(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Set<Tuple>>>() {

			@Override
			Response<Set<Tuple>> execute(Pipeline jedisPipeline) throws DynoException {
				return zrevrangeWithScores(key, start, end);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> zrevrank(final String key, final String member) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zrevrank(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Double> zscore(final String key, final String member) {
		return new PipelineOperation<Response<Double>>() {

			@Override
			Response<Double> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.zscore(key, member);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> bitcount(final String key) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.bitcount(key);
			}

		}.execute(key);

	}

	@Override
	public Response<Long> bitcount(final String key, final long start, final long end) {
		return new PipelineOperation<Response<Long>>() {

			@Override
			Response<Long> execute(Pipeline jedisPipeline) throws DynoException {
				return jedisPipeline.bitcount(key, start, end);
			}

		}.execute(key);

	}

	public void sync() {
		try {
			jedisPipeline.sync();
		} finally {
			discardPipelineAndReleaseConnection();
		}
	}

	private void discardPipeline() {

		try { 
			if (jedisPipeline != null) {
				jedisPipeline.sync();
				jedisPipeline = null;
			}
		} catch (Exception e) {
			Logger.warn("Failed to discard jedis pipeline", e);
		}
	}

	private void releaseConnection() {
		if (connection != null) {
			try {
				connection.getContext().reset();
				connection.getParentConnectionPool().returnConnection(connection);
				if (pipelineEx.get() != null) {
					connPool.getCPHealthTracker().trackConnectionError(connection.getParentConnectionPool(), pipelineEx.get());
					pipelineEx.set(null);
				}
				connection = null;
			} catch (Exception e) {
				Logger.warn("Failed to return connection in Dyno Jedis Pipeline", e);
			}
		}
	}

	public void discardPipelineAndReleaseConnection() {
		discardPipeline();
		releaseConnection();
	}

	@Override
	public void close() throws Exception {
		discardPipelineAndReleaseConnection();
	}
}
