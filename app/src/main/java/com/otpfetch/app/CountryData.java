package com.otpfetch.app;

/**
 * Per-country name lists + regional Outlook domains.
 *
 * Each country has male / female first names and last names.
 * To extend to 30-40 names per gender: just append more strings
 * to the arrays below — no other code changes needed.
 *
 * USER NAMES: paste your own 30-40 female + 30-40 male names per
 * country into these arrays (replace or append).
 */
public final class CountryData {

    private CountryData() {}

    public static final class Country {
        public final String name;    // display name
        public final String domain;  // regional outlook domain
        public final String[] male;
        public final String[] female;
        public final String[] last;
        Country(String name, String domain, String[] male, String[] female, String[] last) {
            this.name = name;
            this.domain = domain;
            this.male = male;
            this.female = female;
            this.last = last;
        }
        @Override public String toString() { return name + " (@" + domain + ")"; }
    }

    public static final Country[] COUNTRIES = {
        new Country("Austria", "outlook.at",
            new String[]{"Lukas","Felix","Jonas","Paul","Leon","Maximilian","David","Tobias","Jakob","Elias","Moritz","Alexander","Simon","Julian"},
            new String[]{"Anna","Marie","Lena","Emma","Mia","Lea","Laura","Sophie","Lina","Hannah","Lisa","Sarah","Julia","Katharina"},
            new String[]{"Gruber","Huber","Wagner","Muller","Pichler","Steiner","Moser","Hofer","Leitner","Berger"}),
        new Country("Indonesia", "outlook.co.id",
            new String[]{"Budi","Agus","Andi","Rizky","Dedi","Hendra","Yoga","Fajar","Ilham","Bagus","Dimas","Eko","Rudi","Wahyu"},
            new String[]{"Siti","Dewi","Putri","Ayu","Rina","Maya","Fitri","Indah","Ratna","Wulan","Sari","Nita","Lestari","Dian"},
            new String[]{"Saputra","Pratama","Kusuma","Santoso","Wijaya","Nugroho","Setiawan","Hidayat","Ramadhan","Gunawan"}),
        new Country("Saudi Arabia", "outlook.sa",
            new String[]{"Mohammed","Abdullah","Fahad","Khalid","Saud","Turki","Salman","Nasser","Majed","Rakan","Yazeed","Faisal","Hassan","Omar"},
            new String[]{"Noura","Sara","Reem","Lama","Jouri","Lina","Rana","Dana","Huda","Amani","Shatha","Maha","Raghad","Wejdan"},
            new String[]{"Alotaibi","Alharbi","Aldossari","Alqahtani","Alshammari","Almutairi","Alghamdi","Alzahrani","Alsubai","Almalki"}),
        new Country("Japan", "outlook.jp",
            new String[]{"Haruto","Sota","Yuto","Ren","Sora","Kaito","Sho","Riku","Koki","Daiki","Takeshi","Kenji","Hiroshi","Takumi"},
            new String[]{"Yui","Aoi","Mei","Hina","Sakura","Akari","Mio","Rin","Yuna","Koharu","Emi","Yuko","Nanami","Aya"},
            new String[]{"Sato","Suzuki","Takahashi","Tanaka","Watanabe","Yamamoto","Nakamura","Kobayashi","Ito","Kato"}),
        new Country("Chile", "outlook.cl",
            new String[]{"Matias","Benjamin","Vicente","Joaquin","Martin","Agustin","Cristobal","Diego","Felipe","Sebastian","Nicolas","Tomas","Lucas","Gabriel"},
            new String[]{"Sofia","Isidora","Emilia","Florencia","Antonia","Martina","Catalina","Valentina","Josefa","Trinidad","Fernanda","Camila","Daniela","Paula"},
            new String[]{"Gonzalez","Munoz","Rojas","Diaz","Perez","Soto","Contreras","Silva","Martinez","Sepulveda"}),
        new Country("Turkey", "outlook.com.tr",
            new String[]{"Mehmet","Mustafa","Ahmet","Emre","Burak","Can","Deniz","Kerem","Mert","Onur","Serkan","Tolga","Yusuf","Emir"},
            new String[]{"Zeynep","Elif","Merve","Fatma","Ayse","Ebru","Selin","Deniz","Burcu","Cansu","Gizem","Pinar","Seda","Yagmur"},
            new String[]{"Yilmaz","Kaya","Demir","Sahin","Celik","Yildiz","Yildirim","Ozturk","Aydin","Ozdemir"}),
        new Country("Thailand", "outlook.co.th",
            new String[]{"Somchai","Nattapong","Anan","Krit","Thanapon","Jirayu","Supachai","Wichai","Chai","Pong","Arthit","Somsak","Teerawat","Nopparat"},
            new String[]{"Siriporn","Nicha","Patchara","Malee","Supaporn","Chutima","Waraporn","Kanya","Pimchanok","Ratree","Ornicha","Duangjai","Naphat","Suda"},
            new String[]{"Srisai","Wongsa","Chaiya","Thongdee","Rattanakul","Panyawong","Suksri","Kamsai","Boonmee","Inthawong"}),
        new Country("Germany", "outlook.de",
            new String[]{"Lukas","Felix","Jonas","Paul","Leon","Finn","Elias","Noah","Ben","Luca","Max","Tim","Jan","Philipp"},
            new String[]{"Mia","Emma","Hannah","Sofia","Anna","Lea","Lena","Marie","Lina","Amelie","Emily","Clara","Luisa","Johanna"},
            new String[]{"Muller","Schmidt","Schneider","Fischer","Weber","Meyer","Wagner","Becker","Schulz","Hoffmann"}),
        new Country("India", "outlook.in",
            new String[]{"Aarav","Arjun","Rohan","Vikram","Aditya","Rahul","Suresh","Amit","Karan","Nikhil","Ravi","Sanjay","Deepak","Manoj"},
            new String[]{"Priya","Ananya","Divya","Kavya","Meera","Pooja","Riya","Sneha","Anjali","Lakshmi","Neha","Shreya","Kavitha","Aishwarya"},
            new String[]{"Sharma","Verma","Patel","Gupta","Singh","Reddy","Nair","Iyer","Khan","Das"}),
        new Country("Italy", "outlook.it",
            new String[]{"Alessandro","Lorenzo","Mattia","Leonardo","Francesco","Andrea","Gabriele","Marco","Luca","Davide","Simone","Federico","Riccardo","Tommaso"},
            new String[]{"Sofia","Giulia","Aurora","Alice","Ginevra","Emma","Giorgia","Beatrice","Vittoria","Ludovica","Matilde","Camilla","Chiara","Sara"},
            new String[]{"Rossi","Russo","Ferrari","Esposito","Bianchi","Romano","Colombo","Ricci","Marino","Greco"}),
        new Country("South Korea", "outlook.kr",
            new String[]{"Minjun","Seojun","Dojun","Siwoo","Jian","Junseo","Donghyun","Taehyun","Jihoon","Sungmin","Jaeho","Hyunwoo","Kyungsoo","Woojin"},
            new String[]{"Seoyeon","Hayeon","Jiwon","Minseo","Yuna","Chaewon","Hana","Nayeon","Dahye","Eunji","Soojin","Yerin","Jieun","Hyejin"},
            new String[]{"Kim","Lee","Park","Choi","Jung","Kang","Cho","Yoon","Shin","Han"}),
        new Country("Latvia", "outlook.lv",
            new String[]{"Janis","Arturs","Miks","Ralfs","Emils","Daniels","Gustavs","Kristers","Markuss","Roberts","Aleksandrs","Andris","Edgars","Karlins"},
            new String[]{"Sofija","Emilija","Anna","Marta","Alise","Elza","Mia","Evelina","Paula","Amanda","Laura","Kate","Daniela","Renate"},
            new String[]{"Berzins","Kalninsh","Ozolins","Jansons","Ozols","Liepa","Klavins","Krumins","Eglitis","Vitols"}),
        new Country("Malaysia", "outlook.my",
            new String[]{"Ahmad","Faiz","Hakim","Irfan","Syafiq","Danial","Haziq","Amir","Farhan","Nabil","Ridwan","Shafiq","Zaki","Imran"},
            new String[]{"Aina","Farah","Hana","Iman","Nadia","Sara","Wani","Zara","Alya","Mira","Dina","Liyana","Syifa","Amira"},
            new String[]{"Abdullah","Rahman","Ibrahim","Hassan","Ali","Ahmad","Yusof","Ismail","Osman","Karim"}),
        new Country("Philippines", "outlook.ph",
            new String[]{"Jose","Miguel","Rafael","Andres","Emmanuel","Gabriel","Paolo","Marco","Enrico","Danilo","Ramon","Felipe","Carlo","Jomari"},
            new String[]{"Maria","Angelica","Bianca","Carmen","Danica","Elena","Francesca","Gabriela","Isabel","Josefa","Katrina","Lucia","Marisol","Rosa"},
            new String[]{"Santos","Reyes","Cruz","Bautista","Ocampo","Garcia","Mendoza","Torres","Tomas","Castillo"}),
        new Country("Portugal", "outlook.pt",
            new String[]{"Joao","Miguel","Martim","Rodrigo","Santiago","Francisco","Afonso","Tomas","Duarte","Tiago","Rafael","Gabriel","Diogo","Pedro"},
            new String[]{"Maria","Leonor","Matilde","Beatriz","Mariana","Carolina","Camila","Ines","Sofia","Margarida","Clara","Alice","Francisca","Lara"},
            new String[]{"Silva","Santos","Ferreira","Pereira","Oliveira","Costa","Rodrigues","Martins","Jesus","Sousa"}),
        new Country("Vietnam", "outlook.com.vn",
            new String[]{"Minh","Hoang","Duc","Anh","Bao","Khoa","Nam","Tuan","Hai","Long","Thanh","Quang","Huy","Viet"},
            new String[]{"Anh","Linh","Ngoc","Trang","Hanh","Thao","Mai","Lan","Huong","Thu","Quynh","Yen","My","Phuong"},
            new String[]{"Nguyen","Tran","Le","Pham","Hoang","Huynh","Phan","Vu","Vo","Dang"}),
        new Country("Czechia", "outlook.cz",
            new String[]{"Jan","Jakub","Tomas","Filip","Vojtech","Ondrej","Matyas","Adam","Lukas","David","Daniel","Martin","Petr","Pavel"},
            new String[]{"Eliska","Tereza","Adela","Anna","Natalie","Karolina","Kristyna","Barbora","Lucie","Veronika","Michaela","Sara","Emma","Nela"},
            new String[]{"Novak","Svoboda","Novotny","Dvorak","Cerny","Prochazka","Kucera","Horak","Nemec","Maly"}),
        new Country("Ireland", "outlook.ie",
            new String[]{"Jack","Liam","Conor","Sean","James","Daniel","Cian","Oisin","Fionn","Tadhg","Callum","Rory","Patrick","Eoin"},
            new String[]{"Saoirse","Aoife","Niamh","Caoimhe","Ciara","Aisling","Roise","Sinead","Clodagh","Maeve","Orla","Fiona","Eimear","Brid"},
            new String[]{"Murphy","Kelly","Byrne","Ryan","Oconnor","Walsh","Doyle","Mccarthy","Brennan","Kennedy"}),
        new Country("Slovakia", "outlook.sk",
            new String[]{"Jakub","Martin","Samuel","Michal","Adam","Tomas","Filip","Lukas","Daniel","Peter","Matej","Oliver","Simon","David"},
            new String[]{"Sofia","Nina","Ema","Viktoria","Natalia","Tereza","Laura","Michaela","Klara","Sara","Emma","Hana","Dominika","Alexandra"},
            new String[]{"Horvath","Kovac","Varga","Toth","Nagy","Balazs","Molnar","Szabo","Kiss","Fekete"}),
        new Country("New Zealand", "outlook.co.nz",
            new String[]{"Oliver","Jack","William","James","Mason","Noah","Liam","Lucas","Ethan","Aiden","Logan","Caleb","Ryan","Hunter"},
            new String[]{"Olivia","Charlotte","Sophie","Isla","Ruby","Ava","Mia","Ella","Grace","Chloe","Emily","Sophia","Lily","Amelia"},
            new String[]{"Smith","Jones","Williams","Brown","Wilson","Taylor","Johnson","White","Martin","Thompson"}),
        new Country("Greece", "outlook.com.gr",
            new String[]{"Georgios","Nikolaos","Dimitrios","Ioannis","Konstantinos","Christos","Panagiotis","Vasileios","Athanasios","Michail","Andreas","Emmanouil","Theodoros","Spyridon"},
            new String[]{"Maria","Eleni","Aikaterini","Vasiliki","Sofia","Georgia","Anastasia","Ioanna","Dimitra","Evangelia","Panagiota","Konstantina","Christina","Nikoleta"},
            new String[]{"Papadopoulos","Papanikolaou","Papadopoulou","Karagiannis","Georgiou","Nikolaou","Dimitriou","Christou","Ioannou","Vasileiou"}),
        new Country("Spain", "outlook.es",
            new String[]{"Hugo","Mateo","Martin","Leo","Lucas","Manuel","Daniel","Alejandro","Pablo","Adrian","Alvaro","David","Mario","Diego"},
            new String[]{"Lucia","Sofia","Martina","Maria","Julia","Paula","Valeria","Alba","Daniela","Carmen","Vega","Clara","Emma","Alma"},
            new String[]{"Garcia","Fernandez","Gonzalez","Rodriguez","Lopez","Martinez","Sanchez","Perez","Gomez","Martin"}),
        new Country("Denmark", "outlook.dk",
            new String[]{"William","Oliver","Noah","Emil","Victor","Magnus","Frederik","Mikkel","Lucas","Alexander","Oscar","Liam","Malthe","Elias"},
            new String[]{"Ida","Emma","Ella","Sofia","Freja","Clara","Alma","Olivia","Anna","Josefine","Agnes","Karla","Maja","Ellie"},
            new String[]{"Nielsen","Jensen","Hansen","Pedersen","Andersen","Christensen","Larsen","Sorensen","Rasmussen","Jorgensen"}),
        new Country("Argentina", "outlook.com.ar",
            new String[]{"Mateo","Thiago","Bautista","Benjamin","Dylan","Santino","Bruno","Tomas","Valentino","Joaquin","Lautaro","Thiago","Facundo","Santiago"},
            new String[]{"Martina","Catalina","Emma","Olivia","Sofia","Isabella","Mia","Victoria","Valentina","Renata","Camila","Juana","Alfonsina","Morena"},
            new String[]{"Gonzalez","Fernandez","Lopez","Diaz","Martinez","Perez","Sanchez","Ramirez","Garcia","Gomez"}),
        new Country("Belgium", "outlook.be",
            new String[]{"Louis","Jules","Liam","Arthur","Adam","Gabriel","Raphael","Victor","Noah","Mohamed","Lucas","Hugo","Nathan","Ethan"},
            new String[]{"Olivia","Mila","Emma","Louise","Alice","Lina","Mia","Julia","Elena","Chloe","Lena","Anna","Nina","Sarah"},
            new String[]{"Peeters","Janssens","Maes","Jacobs","Mertens","Willems","Claes","Goossens","Wouters","De Smet"}),
        new Country("Australia", "outlook.com.au",
            new String[]{"Oliver","Jack","Noah","William","Leo","Henry","Charlie","Lucas","Thomas","Liam","Mason","Ethan","Harrison","Cooper"},
            new String[]{"Olivia","Charlotte","Ava","Mia","Isla","Ruby","Grace","Sophie","Chloe","Ella","Amelia","Sophia","Lily","Matilda"},
            new String[]{"Smith","Jones","Williams","Brown","Wilson","Taylor","Johnson","White","Martin","Anderson"}),
        new Country("Brazil", "outlook.com.br",
            new String[]{"Miguel","Theo","Gael","Arthur","Heitor","Bernardo","Davi","Valentin","Gabriel","Pedro","Enzo","Matheus","Lucas","Gustavo"},
            new String[]{"Helena","Alice","Laura","Valentina","Sophia","Isabella","Manuela","Julia","Lorena","Cecilia","Beatriz","Maria","Clara","Heloisa"},
            new String[]{"Silva","Santos","Oliveira","Souza","Rodrigues","Ferreira","Almeida","Carvalho","Gomes","Martins"}),
        new Country("Israel", "outlook.co.il",
            new String[]{"Ariel","Noam","Eitan","David","Yosef","Daniel","Omer","Uri","Itay","Guy","Tomer","Alon","Idan","Lior"},
            new String[]{"Noa","Tamar","Maya","Yael","Michal","Adi","Shira","Tal","Hila","Roni","Liat","Dana","Noga","Orit"},
            new String[]{"Cohen","Levi","Mizrahi","Peretz","Biton","Dahan","Azulay","Gabay","Ohana","Amsalem"}),
        new Country("Hungary", "outlook.hu",
            new String[]{"Bence","Mate","Dominik","Levente","Noel","Daniel","Zalan","Marcell","Olivér","Adam","Milán","Dávid","Botond","Benett"},
            new String[]{"Hanna","Anna","Emma","Zoe","Luca","Lena","Mira","Lili","Olivia","Nora","Lilien","Boglarka","Fanni","Izabella"},
            new String[]{"Nagy","Kovacs","Toth","Nemes","Kiss","Horvath","Varga","Szabo","Molnar","Németh"}),
        new Country("Singapore", "outlook.sg",
            new String[]{"Wei","Jun","Hao","Zhi","Kai","Ming","Chen","Jian","Zi","Hong","Leon","Marcus","Ryan","Ethan"},
            new String[]{"Mei","Jia","Xin","Yi","Hui","Li","An","Grace","Sarah","Chloe","Alyssa","Natalie","Vanessa","Jasmine"},
            new String[]{"Tan","Lim","Lee","Ng","Ong","Wong","Goh","Chan","Koh","Teo"}),
        new Country("France", "outlook.fr",
            new String[]{"Louis","Gabriel","Raphael","Jules","Adam","Lucas","Leo","Hugo","Arthur","Nathan","Ethan","Enzo","Mael","Noah","Eden","Liam","Marius","Gaspard","Victor","Paul","Antoine","Clement","Maxence","Theophile","Baptiste","Augustin","Felix","Emile","Henri","Valentin","Tristan","Eliott","Noe","Sacha","Martin","Rayan"},
            new String[]{"Louise","Jade","Emma","Alice","Chloe","Mia","Lina","Lea","Anna","Julia","Elena","Rose","Juliette","Camille","Sarah","Ines","Eva","Lena","Manon","Margaux","Pauline","Victoire","Agathe","Eleonore","Constance","Blanche","Colette","Suzanne","Madeleine","Capucine","Apolline","Sixtine","Garance","Olympe","Zoe","Nina"},
            new String[]{"Martin","Bernard","Thomas","Petit","Robert","Richard","Durand","Dubois","Moreau","Laurent","Simon","Michel","Lefebvre","Leroy","Roux","David","Bertrand","Morel","Fournier","Girard"}),
    };

    /** Display names for spinners, e.g. "Germany (@outlook.de)". */
    public static String[] displayNames() {
        String[] out = new String[COUNTRIES.length];
        for (int i = 0; i < COUNTRIES.length; i++) out[i] = COUNTRIES[i].toString();
        return out;
    }

    public static Country byIndex(int i) {
        if (i < 0 || i >= COUNTRIES.length) return COUNTRIES[0];
        return COUNTRIES[i];
    }
}
