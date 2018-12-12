compile:
	lein -U do deps, clean, compile

test: compile
	lein test

start: compile
	lein repl

testmultiple: compile
	sh ./tools/runtests.sh

.PHONY: test
