#cp /Users/swalter/Git/matoll/matoll/example9.ttl .

#cp /Users/swalter/Documents/dbpedia2014_beforeTraining.ttl .
rm ../all.nt.gz
#rapper -o ntriples -i turtle example9.ttl | gzip >> ../all.nt.gz
rapper -o ntriples -i turtle dbpedia2014_beforeTraining.ttl > all.nt
python clean.py
cat all_new.nt | gzip >> ../all.nt.gz
sh run.sh
