for load_config in *-loaded
do
	for run in $load_config/3600*
	do
		echo ===========================================================
		echo $run
		echo "individual candidate LOAD count"
		for file in $run/mtd-zk-*.csv
		do
			cat $file | grep LOAD | wc -l
		done
		echo "total ensemble START count"
		cat "${run}/mtd-zk-"* | grep START | wc -l
		echo "first lines"
    head "${run}/"* -n1 | grep -oP '\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}'
    echo "last lines"
    tail "${run}/"* -n1 | grep -oP '\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}'
		echo ===========================================================
	done
done
