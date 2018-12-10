compile:
	lein -U do deps, clean, compile

test: compile
	lein test

start: compile
	lein repl

.PHONY: test