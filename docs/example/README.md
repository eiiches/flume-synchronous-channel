Example
=======

### Run

```console
$ docker-compose up
```

To send some events:

```console
$ nc localhost 44444
foo
OK
```

Events are printed in the Flume log.
