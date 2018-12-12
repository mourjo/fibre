for i in `seq 1 1000`; do
	printf "\n************************************\nTEST RUN: $i \n"
	date
	printf "************************************\n"
	lein test;
	printf "\n"
done;
