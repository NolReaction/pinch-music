# Музыка

Сразу после установки доступны восемь песен. У Stereo Love есть инструментальная аранжировка на 3:04, у остальных семи песен — короткие фразы для игры одним пальцем. У каждой партии свой темп. Шарик касается планки в момент, заданный партитурой. В Stereo Love мелодия записана вместе с сопровождением. В коротких фразах и импортированных партиях используются сэмплы выбранного инструмента.

Каталог ведётся в [SongLibrary.kt](../composeApp/src/commonMain/kotlin/music/pinch/SongLibrary.kt). У Stereo Love партия игрока и сопровождение используют [общую партитуру](../tools/stereo_love/score.py). По ней можно проверить игровые панели командой `python3 tools/stereo_love/score.py --check`.

| Песня | Партия для игры | Музыкальный ориентир |
| --- | --- | --- |
| On the Floor — Jennifer Lopez ft. Pitbull | Нисходящий мотив из «Llorando se fue», знакомый по «Lambada». Перенесён в ре минор. | [Мелодия](https://melodicnotes.com/song/jennifer-lopez-on-the-floor-4003/) |
| Stereo Love — Edward Maya & Vika Jigulina | 127 BPM, 3:04. Аккордеон, бас и ударные ведут игру от вступления до коды. Шарик встречает 508 нот, из них 159 акцентов отбивает игрок. | [Mark Richards, пример 14](https://mtosmt.org/issues/mto.17.23.3/mto.17.23.3.richards.html) |
| Gypsy Woman — Crystal Waters | Отдельные ступени органных аккордов, включая хроматическое движение ми – ми-бемоль – ре. | [Гармония органного риффа](https://dancenastysmusicblog.wordpress.com/2019/05/03/crystal-waters-gypsy-woman-a-transcription/) |
| Levels — Avicii | Две нисходящие фразы синтезатора с разными окончаниями. | [Rick Snoman, Dance Music Manual, пример 1.5](https://api.pageplace.de/preview/DT0400.9783840313257_A38251302/preview-9783840313257_A38251302.pdf) |
| Stay — The Kid LAROI & Justin Bieber | Контур вокальной фразы припева. | [Нотный ориентир](https://easyletternotes.wordpress.com/2021/09/09/stay-the-kid-laroi-justin-bieber/) |
| Turn Down for What — DJ Snake & Lil Jon | Октавный ответ синтезатора на повторяющейся нижней ноте. | [Сравнительный нотный пример, нижняя строка, стр. 21–22](https://bpb-us-w2.wpmucdn.com/blogs.law.gwu.edu/dist/a/4/files/2018/12/GoldenCrownComplaint-1hsgroi.pdf#page=21) |
| Yeah! — Usher ft. Lil Jon & Ludacris | Синтезатор играет от соль с чередованием ре и ми-бемоля. | [Разбор риффа](https://muzikum.eu/index.php/en/usher/yeah-piano-tab) |
| The Final Countdown — Europe | Два коротких оборота из вступления синтезатора в фа-диез миноре. | [Вступление](https://unpianiste.com/wp-content/uploads/partitions/The_Final_Countdown_Intro.pdf) |

## Свой аудиофайл

Выбери песню и открой аудиофайл через системное меню. Анализатор берёт до 30 секунд, оценивает высоту доминирующего тона в коротких перекрывающихся окнах и объединяет их в ноты. На одинаковой высоте он учитывает и повторную атаку звука. Близкие к соседнему прыжку ноты прореживаются. Из плотного микса алгоритм иногда выбирает бас или аккомпанемент. Для конкретного куплета или припева можно выбрать короткий файл с этим фрагментом.

Игра запоминает извлечённые ноты на устройстве. При следующем запуске их можно играть любым из трёх инструментов. Для возврата к исходной партии выбери восстановление встроенной версии.
