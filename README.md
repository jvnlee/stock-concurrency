# 재고 관리 예제로 알아보는 동시성 문제 해결

## 1. Synchronized

```java
@Transactional
public synchronized void decrease(Long id, Long quantity) {
    Stock stock = stockRepository.findById(id).orElseThrow();

    stock.decrease(quantity);

    stockRepository.saveAndFlush(stock);
}
```

메서드 레벨에 `synchronized` 키워드를 사용해서 한번에 하나의 쓰레드만 `decrease()` 메서드에 접근할 수 있게 해도 여전히 동시성 문제가 발생함.

원인은 `@Transactional` 어노테이션으로, Spring AOP에 의해 트랜잭션이 시작하고 종료하는 과정 안에서 다른 쓰레드가 `decrease()`를 호출할 수 있기 때문.

```java
class StockServiceAop {
    private StockService stockService;

    public void decrease(Long id, Long quantity) {
        // 트랜잭션 시작
        stockService.decrease();
        // !!
        // 트랜잭션 종료
    }
}
```

위 더미 코드는 트랜잭션 AOP를 보여주는데, 트랜잭션이 종료될 때 DB에 수정된 재고 값이 반영됨.

그러나 주석 `!!`로 표시한 부분 즉 `decrease()` 호출이 끝난 직후와 트랜잭션 종료 시점 사이에 다른 쓰레드가 `decrease()`를 호출하는 것이 가능함.
그렇게 되면 수정된 재고 값이 반영되기 전에 다른 쓰레드가 변경 전의 재고값을 가지고 `decrease()`를 실행하기 때문에 업데이트 누락이 발생하게 됨.

`@Transactional`을 제거하면 동시성 문제 없이 정상적으로 동작함

&nbsp;

### ⚠️ 문제점

그러나 `synchronized`는 하나의 프로세스에 대해서만 동기화를 보장함.

따라서 서버 인스턴스가 여러 개인 경우에는 동시성 문제가 여전히 발생할 수 있음

&nbsp;

## 2. DB Lock

DB 레벨에서 락을 걸어 데이터 정합성을 보장하는 방식

### 2-1. Pessimistic Lock

동시성 문제가 발생한다는 전제가 깔린 비관적인 락 방식

비관적 락을 사용하면 오직 하나의 쓰레드만 데이터를 수정할 수 있기 때문에 동시성 문제가 발생하지 않음

여러 대의 서버가 하나의 DB를 공유하는 경우에도 DB 레벨에서 락을 걸어주기 때문에 다른 서버의 쓰레드가 동시에 같은 자원에 접근할 수 없게 됨.

데이터의 수정에 대해서만 배타적 권한을 주는 것이기 때문에, 다른 쓰레드가 데이터를 조회하는 것에 대해서는 제약이 없음.

&nbsp;

### ⚠️ 문제점

데드 락 발생 가능

MySQL의 경우 데드 락을 방지 혹은 해결할 수 있는 방식을 제공함

1. lock ordering

    여러 자원에 대한 락이 서로 얽혀 데드락 상황이 발생하는 것을 막기 위해 락을 일관되고 예측 가능한 순서대로 획득할 수 있게 세팅할 수 있음
    > lock manager가 이 역할을 수행함


2. dead lock detection

    MySQL은 내부적으로 데드 락을 감지할 수 있도록 설계되어 있음. 데드 락이 감지되면 가장 적은 양의 작업을 가진 트랜잭션을 자체적으로 롤백시키고 관련된 락을 모두 해제시킴.
    이렇게 되면 막혀있던 나머지 트랜잭션들이 필요한 작업을 수행할 수 있게 됨 


3. timeout & retry

    데드 락이 무한정 지속되는 것을 막기 위해 트랜잭션에 timeout을 걸어 지정한 시간이 지나면 트랜잭션을 롤백할 수 있음. 그러면 관련된 락이 해제되면서 막혀있던 다른 트랜잭션이 필요한 작업을 수행할 수 있게됨.
    데드 락으로 인해 실패한 트랜잭션이 있는 경우, 지정한 횟수 만큼 retry를 할 수 있음.

&nbsp;

### 2-2. Optimistic Lock

동시성 문제가 거의 발생하지 않는다는 전제가 깔린 낙관적인 락 방식

버전 관리로 데이터 정합성이 망가지는 것을 방지함. 데이터를 읽어올 때의 해당 데이터의 버전과 수정을 마친 후에 다시 확인한 버전이 일치하는 경우에만 업데이트를 진행시킴.
만약 버전이 맞지 않다면, 내가 읽어온 후에 다른 누군가가 수정한 것이므로 내 업데이트를 반영하지 않고 롤백시킴.

일반적으로 버전을 나타내는 별도의 컬럼을 두어 이를 참조함.

&nbsp;

### ⚠️ 문제점

- 공유된 자원에 대한 접근이 빈번한 환경에는 적합하지 않음

    > 잦은 롤백이 발생해서 효율이 좋지 않음

- 버전 체킹으로 인한 오버헤드

- 애플리케이션 레벨의 코드 복잡도 증가

    > 버전 불일치 시 롤백 처리를 애플리케이션 레벨에서 해야하기 때문 

&nbsp;

## 3. Redis

In-Memory 방식의 Redis DB를 가지고 락을 사용할 수 있음

일반적으로 위에서 살펴본 MySQL을 활용한 DB 락 방식보다는 성능이 좋지만, Redis라는 별도의 인프라를 구축 및 관리 비용이 발생하므로 득실을 잘 따져서 도입하면 됨.

### 3-1. Lettuce

Redis의 기본 client 라이브러리이므로, 별도의 라이브러리를 설치할 필요 없음.

Redis DB에 key-value 데이터를 저장해서 락의 역할을 하도록 하고, 작업이 끝나면 폐기하는 방식

Spin Lock 방식을 사용함.

> **Spin Lock**
> 
> 락을 획득할 때까지 반복적으로 락의 획득 가능 여부를 체크하며 기다리는 방식.

내부적으로는 Redis의 `SETNX` 명령어(SET if Not eXists)를 사용함. (애플리케이션 레벨에서는 RedisTemplate으로 구현)

```java
redisTemplate
        .opsForValue()
        .setIfAbsent(generateKey(key), "lock", Duration.ofMillis(3000)); // SETNX
```

부하 조절을 위해 락 획득을 시도하는 루프에서 `Thread.sleep()`을 사용

```java
while (!redisLockRepository.lock(key)) {
    Thread.sleep(100); // 0.1초마다 재시도
}
```

&nbsp;

### ⚠️ 문제점

많은 수의 쓰레드가 락 획득을 시도하게 되면, Redis에 부하가 발생할 수 있음.

> 따라서, 락 획득 재시도가 필요하지 않은 경우에 사용하는 것이 적합함.

&nbsp;

### 3-2. Redisson

Lettuce와 달리 기본 제공 라이브러리가 아니므로, 별도로 설치해서 사용해야함.

Lettuce처럼 임의의 key-value를 가지고 락처럼 활용하는 것이 아니라, 라이브러리 차원에서 제공해주는 락 객체를 활용함.

Pub-Sub 방식을 사용함

> **Pub-Sub**
> 
> 한 쪽에서는 이벤트를 발행(Publish)하고, 다른 쪽에서는 발행되는 이벤트를 구독(Subscribe)하는 형태
> 
> 구독자는 이벤트가 발행되기를 기다리고 있다가, 발행되면 그것을 캐치해서 지정된 작업을 수행함.
> 
> 어떤 쓰레드가 락을 사용한 뒤 해제하면, 다른 쓰레드들은 락이 해제되길 기다렸다가, 해제되는 즉시 락 획득을 시도하는 것.

Pub-Sub 방식을 사용하기 때문에 Lettuce를 사용하는 것과 비교했을 때, Redis에 부하가 훨씬 덜함.

내부적으로는 Redis의 `PUBLISH`와 `SUBSCRIBE` 명령어를 사용함.

```java
RLock lock = redissonClient.getLock(key.toString()); // 락 객체

try {
    boolean available = lock.tryLock(5, 1, TimeUnit.SECONDS); // 락 해제 이벤트 SUBSCRIBE

    if (!available) {
        log.info("LOCK 획득 실패!");
        return;
    }

    stockService.decrease(key, quantity);
} catch (InterruptedException e) {
    throw new RuntimeException(e);
} finally {
    lock.unlock(); // 락 해제 이벤트 PUBLISH
}
```

`tryLock()` 메서드

```java
public abstract boolean tryLock(
    long waitTime,
    long leaseTime,
    java.util.concurrent.TimeUnit unit
) { ... }
```

- `waitTime`만큼 락 획득 대기 상태(락 해제 이벤트 subscribe)에 있음
- `leaseTime`만큼만 락을 가지고 있을 수 있음 (이 시간이 지나면 즉시 해제)