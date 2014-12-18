import csv

r1 = csv.DictReader(open("stops1.txt", "r+"))
r2 = csv.DictReader(open("stops2.txt", "r+"), delimiter='\t')

w = csv.DictWriter(open("stops.txt", "w+"), r1.fieldnames)
w.writeheader()

stops = {row['stop_id']: row for row in r2}

for line in r1:
	id = line['stop_id']
	if line['stop_id'] in stops:
		line['stop_name'] = stops[id]['stop_name'] 
		#print "%s -> %s" % (line['stop_name'], stops[id]['stop_name'])
	else: print "NOT FOUND"

	w.writerow(line)	

